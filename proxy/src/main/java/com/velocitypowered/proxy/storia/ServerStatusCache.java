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

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Pings every backend server periodically so placeholders never wait on the network.
 */
final class ServerStatusCache {

  private record Status(boolean online, String motd, String version, int max, long latencyMillis) {}

  private static final Status OFFLINE = new Status(false, "", "-", 0, -1);

  private final VelocityServer server;
  private final Map<String, Status> statuses = new ConcurrentHashMap<>();

  ServerStatusCache(final VelocityServer server) {
    this.server = server;
  }

  void refresh() {
    for (final RegisteredServer backend : this.server.getAllServers()) {
      final String name = backend.getServerInfo().getName();
      final long start = System.nanoTime();
      backend.ping().whenComplete((final ServerPing ping, final Throwable error) -> {
        if (error != null || ping == null) {
          this.statuses.put(name, OFFLINE);
          return;
        }
        this.statuses.put(name, new Status(true,
            PlainTextComponentSerializer.plainText().serialize(ping.getDescriptionComponent()).replace('\n', ' ').trim(),
            ping.getVersion().getName(),
            ping.getPlayers().map(ServerPing.Players::getMax).orElse(0),
            (System.nanoTime() - start) / 1_000_000L));
      });
    }
    this.statuses.keySet().removeIf(name -> this.server.getServer(name).isEmpty());
  }

  private Status get(final String name) {
    return this.statuses.getOrDefault(name, OFFLINE);
  }

  boolean isOnline(final String name) {
    return this.get(name).online();
  }

  int onlineCount() {
    return (int) this.statuses.values().stream().filter(Status::online).count();
  }

  String motd(final String name) {
    return this.get(name).motd();
  }

  String version(final String name) {
    return this.get(name).version();
  }

  String max(final String name) {
    return String.valueOf(this.get(name).max());
  }

  String latency(final String name) {
    final long latency = this.get(name).latencyMillis();
    return latency < 0 ? "-" : String.valueOf(latency);
  }
}
