/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.storia;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.offload.protocol.Messages;
import dev.storia.offload.protocol.SecureChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Connects Storia Proxy to a Storia Cluster coordinator. Joining players are sent to the node that runs the
 * area they left from, and the coordinator can move players between nodes when areas merge or for balance.
 * Backend server names in velocity.toml must match the nodes' {@code cluster.node-name}.
 */
public final class StoriaCluster {

  private static final Logger LOGGER = LogManager.getLogger(StoriaCluster.class);

  private final VelocityServer server;
  private final String host;
  private final int port;
  private final String secret;
  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, CompletableFuture<ClusterProtocol.Response>> pending = new ConcurrentHashMap<>();
  private final AtomicLong moves = new AtomicLong();
  private volatile @Nullable SecureChannel channel;
  private volatile boolean closed;

  /**
   * Starts the cluster connection.
   *
   * @param server the proxy
   * @param coordinator host:port of the coordinator
   * @param secret the shared secret
   */
  public StoriaCluster(final VelocityServer server, final String coordinator, final String secret) {
    this.server = server;
    final int colon = coordinator.lastIndexOf(':');
    this.host = colon < 0 ? coordinator : coordinator.substring(0, colon);
    this.port = colon < 0 ? 25590 : Integer.parseInt(coordinator.substring(colon + 1));
    this.secret = secret;
    server.getEventManager().register(VelocityVirtualPlugin.INSTANCE, this);
    final Thread connector = new Thread(this::connectLoop, "Storia Cluster");
    connector.setDaemon(true);
    connector.start();
  }

  private void connectLoop() {
    while (!this.closed) {
      try {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(this.host, this.port), 5000);
        socket.setTcpNoDelay(true);
        final SecureChannel channel = SecureChannel.initiate(socket, this.secret, true);
        channel.send(Messages.hello(new Messages.Hello(ClusterProtocol.ROLE_PROXY, 0, Map.of("proxy", "storia-proxy"))));
        final Messages.Welcome welcome = Messages.readWelcome(channel.receive());
        if (!welcome.ok()) {
          throw new IOException(welcome.message());
        }
        this.channel = channel;
        LOGGER.info("Connected to the Storia Cluster coordinator at {}:{}", this.host, this.port);
        this.readLoop(channel);
      } catch (final IOException ex) {
        if (!this.closed) {
          LOGGER.warn("Storia Cluster coordinator at {}:{} unavailable: {}", this.host, this.port, ex.getMessage());
        }
      }
      this.channel = null;
      this.pending.values().forEach(future -> future.completeExceptionally(new IOException("disconnected")));
      this.pending.clear();
      try {
        Thread.sleep(2000L);
      } catch (final InterruptedException ex) {
        return;
      }
    }
  }

  private void readLoop(final SecureChannel channel) throws IOException {
    while (true) {
      final byte[] message = channel.receive();
      final byte type = ClusterProtocol.type(message);
      if (type == ClusterProtocol.RESPONSE) {
        final ClusterProtocol.Response response = ClusterProtocol.readResponse(message);
        final CompletableFuture<ClusterProtocol.Response> future = this.pending.remove(response.id());
        if (future != null) {
          future.complete(response);
        }
      } else if (type == ClusterProtocol.PUSH) {
        final ClusterProtocol.Push push = ClusterProtocol.readPush(message);
        if (push.op() == ClusterProtocol.PUSH_MOVE) {
          final String[] move = ClusterProtocol.readMove(push.body());
          this.move(UUID.fromString(move[0]), move[1]);
        }
      }
    }
  }

  private void move(final UUID uuid, final String node) {
    final Optional<Player> player = this.server.getPlayer(uuid);
    final Optional<RegisteredServer> target = this.server.getServer(node);
    if (player.isEmpty()) {
      return;
    }
    if (target.isEmpty()) {
      LOGGER.warn("Storia Cluster asked to move {} to node '{}', but no server with that name is registered in velocity.toml",
          player.get().getUsername(), node);
      return;
    }
    if (player.get().getCurrentServer().map(s -> s.getServerInfo().getName().equals(node)).orElse(false)) {
      return;
    }
    this.moves.incrementAndGet();
    player.get().createConnectionRequest(target.get()).fireAndForget();
  }

  private ClusterProtocol.Response request(final byte op, final byte[] body, final long timeoutMillis) throws Exception {
    final SecureChannel channel = this.channel;
    if (channel == null) {
      throw new IOException("not connected");
    }
    final long id = this.ids.incrementAndGet();
    final CompletableFuture<ClusterProtocol.Response> future = new CompletableFuture<>();
    this.pending.put(id, future);
    try {
      channel.send(ClusterProtocol.request(new ClusterProtocol.Request(id, op, body)));
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } finally {
      this.pending.remove(id);
    }
  }

  /**
   * Sends a joining player to the node that runs the area they left from.
   *
   * @param event the event
   */
  @Subscribe
  public void onChooseServer(final PlayerChooseInitialServerEvent event) {
    try {
      final ClusterProtocol.Response response = this.request(ClusterProtocol.OP_ROUTE,
          ClusterProtocol.string(event.getPlayer().getUniqueId().toString()), 3000L);
      if (response.status() == ClusterProtocol.OK) {
        final String node = ClusterProtocol.readString(response.body());
        this.server.getServer(node).ifPresent(event::setInitialServer);
      }
    } catch (final Exception ex) {
      LOGGER.warn("Storia Cluster routing failed for {}: {}", event.getPlayer().getUsername(), ex.getMessage());
    }
  }

  /**
   * Status lines for /storiaproxy cluster.
   *
   * @return the lines
   */
  public List<String> status() {
    final List<String> lines = new ArrayList<>();
    lines.add("Storia Cluster coordinator " + this.host + ":" + this.port + (this.channel != null ? " (connected)" : " (DISCONNECTED)")
        + ", " + this.moves.get() + " player move(s) done here");
    try {
      final ClusterProtocol.Response response = this.request(ClusterProtocol.OP_STATUS, new byte[0], 3000L);
      for (final String line : ClusterProtocol.readString(response.body()).split("\n")) {
        lines.add(" " + line);
      }
    } catch (final Exception ex) {
      lines.add(" status unavailable: " + ex.getMessage());
    }
    return lines;
  }

  /** Stops the connection. */
  public void close() {
    this.closed = true;
    final SecureChannel channel = this.channel;
    if (channel != null) {
      channel.close();
    }
  }
}
