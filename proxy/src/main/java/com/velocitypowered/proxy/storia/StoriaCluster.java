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
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.net.Handshake;
import dev.storia.net.SecureChannel;
import io.netty.buffer.ByteBuf;
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

  /** Set when a cluster connection is configured; entity tracking only runs then. */
  private static volatile boolean active;

  /** Clientbound play packet ids needed for seamless switching, per client version. */
  private static final int ADD_ENTITY = 0x01;
  private static final int REMOVE_ENTITIES_26 = 0x4D;

  /**
   * Whether seamless switching knows the packet ids of this client version. Verified against Minecraft 26.2's
   * packet order and Velocity's mappings for 26.1 and 26.2; other versions use a normal switch.
   *
   * @param version the client version
   * @return true if supported
   */
  public static boolean supportsSeamless(final ProtocolVersion version) {
    return version == ProtocolVersion.MINECRAFT_26_1 || version == ProtocolVersion.MINECRAFT_26_2;
  }

  /**
   * The clientbound "remove entities" packet id.
   *
   * @param version the client version
   * @return the packet id
   */
  public static int removeEntitiesPacketId(final ProtocolVersion version) {
    return REMOVE_ENTITIES_26;
  }

  /**
   * Remembers which entities a node has spawned on the client, so they can be removed on a seamless switch.
   * Only reads the packet; never changes it.
   *
   * @param connection the backend connection
   * @param buf the raw packet (id first)
   */
  public static void trackEntities(final VelocityServerConnection connection, final ByteBuf buf) {
    if (!active || !supportsSeamless(connection.getPlayer().getProtocolVersion())) {
      return;
    }
    final int start = buf.readerIndex();
    try {
      final int id = ProtocolUtils.readVarInt(buf);
      if (id == ADD_ENTITY) {
        connection.storiaEntities().add(ProtocolUtils.readVarInt(buf));
      } else if (id == REMOVE_ENTITIES_26) {
        final int count = ProtocolUtils.readVarInt(buf);
        for (int i = 0; i < count && i < 65536; ++i) {
          connection.storiaEntities().remove(ProtocolUtils.readVarInt(buf));
        }
      }
    } catch (final RuntimeException ignored) {
      // not what we expected; leave it alone
    } finally {
      buf.readerIndex(start);
    }
  }

  private final VelocityServer server;
  /** The relays to try, in order: the active relay and its standby (host, port). */
  private final List<Map.Entry<String, Integer>> coordinators = new ArrayList<>();
  private volatile String connectedTo = "";
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
    for (final String address : coordinator.split(",")) {
      final String trimmed = address.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      final int colon = trimmed.lastIndexOf(':');
      this.coordinators.add(Map.entry(colon < 0 ? trimmed : trimmed.substring(0, colon),
          colon < 0 ? 25590 : Integer.parseInt(trimmed.substring(colon + 1))));
    }
    if (this.coordinators.isEmpty()) {
      throw new IllegalArgumentException("[cluster] coordinator is empty");
    }
    this.secret = secret;
    active = true;
    server.getEventManager().register(VelocityVirtualPlugin.INSTANCE, this);
    final Thread connector = new Thread(this::connectLoop, "Storia Cluster");
    connector.setDaemon(true);
    connector.start();
  }

  private void connectLoop() {
    while (!this.closed) {
      SecureChannel channel = null;
      final List<String> failures = new ArrayList<>();
      for (final Map.Entry<String, Integer> address : this.coordinators) {
        try {
          channel = this.open(address.getKey(), address.getValue());
          this.connectedTo = address.getKey() + ":" + address.getValue();
          break;
        } catch (final IOException ex) {
          failures.add(address.getKey() + ":" + address.getValue() + " (" + ex.getMessage() + ")");
        }
      }
      if (channel != null) {
        this.channel = channel;
        LOGGER.info("Connected to the Storia Cluster coordinator at {}", this.connectedTo);
        try {
          this.readLoop(channel);
        } catch (final IOException ex) {
          if (!this.closed) {
            LOGGER.warn("Lost the Storia Cluster coordinator at {}: {}", this.connectedTo, ex.getMessage());
          }
        }
      } else if (!this.closed) {
        LOGGER.warn("No Storia Cluster coordinator available: {}", String.join(", ", failures));
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

  /** Connects to one relay; a standby refuses, and the next relay in the list is tried. */
  private SecureChannel open(final String host, final int port) throws IOException {
    final Socket socket = new Socket();
    socket.connect(new InetSocketAddress(host, port), 5000);
    socket.setTcpNoDelay(true);
    final SecureChannel channel = SecureChannel.initiate(socket, this.secret, true);
    channel.send(Handshake.hello(new Handshake.Hello(ClusterProtocol.ROLE_PROXY, 0, Map.of("proxy", "storia-proxy"))));
    final Handshake.Welcome welcome = Handshake.readWelcome(channel.receive());
    if (!welcome.ok()) {
      channel.close();
      throw new IOException(welcome.message());
    }
    return channel;
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
          try {
            final String[] move = ClusterProtocol.readMove(push.body());
            this.move(UUID.fromString(move[0]), move[1]);
          } catch (final RuntimeException ex) {
            // one bad move must not end the connection to the coordinator
            LOGGER.warn("Storia Cluster: could not carry out a move: {}", ex.toString());
          }
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
    ((ConnectedPlayer) player.get()).storiaMoveSeamlessly(target.get());
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
    lines.add("Storia Cluster coordinator " + (this.channel != null ? this.connectedTo + " (connected)" : "(DISCONNECTED)")
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
