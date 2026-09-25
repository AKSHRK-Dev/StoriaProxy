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

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import dev.storia.proxy.api.StoriaPlaceholders;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The built-in placeholders and the registry plugins add to.
 */
public final class PlaceholderRegistry implements StoriaPlaceholders {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter TIME_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final Map<String, Function<@Nullable Player, String>> exact = new ConcurrentHashMap<>();
  private final Map<String, BiFunction<@Nullable Player, String, String>> prefixes = new ConcurrentHashMap<>();
  private final Map<String, String> descriptions = new ConcurrentHashMap<>();

  PlaceholderRegistry(final VelocityServer server, final ServerStatusCache status, final Map<java.util.UUID, Long> loginTimes) {
    // ---- proxy ----
    this.builtin("proxy_name", "proxy name", p -> server.getVersion().getName());
    this.builtin("proxy_version", "proxy version", p -> server.getVersion().getVersion());
    this.builtin("online", "players on the proxy", p -> String.valueOf(server.getPlayerCount()));
    this.builtin("max", "max players (config show-max-players)", p -> String.valueOf(server.getConfiguration().getShowMaxPlayers()));
    this.builtin("server_count", "registered backend servers", p -> String.valueOf(server.getAllServers().size()));
    this.builtin("servers_online", "backend servers answering pings", p -> String.valueOf(status.onlineCount()));
    this.builtin("servers", "backend server names", p -> server.getAllServers().stream()
        .map(s -> s.getServerInfo().getName()).sorted().collect(Collectors.joining(", ")));
    this.builtin("time", "time HH:mm", p -> LocalDateTime.now().format(TIME));
    this.builtin("time_seconds", "time HH:mm:ss", p -> LocalDateTime.now().format(TIME_SECONDS));
    this.builtin("date", "date yyyy-MM-dd", p -> LocalDateTime.now().format(DATE));
    this.builtin("datetime", "date and time", p -> LocalDateTime.now().format(DATETIME));
    this.builtin("day_of_week", "day of the week (player's language when known)",
        p -> LocalDateTime.now().getDayOfWeek().getDisplayName(TextStyle.FULL, locale(p)));
    this.builtin("uptime", "proxy uptime, e.g. 2d 3h 4m", p -> formatDuration(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime())));
    this.builtin("uptime_seconds", "proxy uptime in seconds", p -> String.valueOf(ManagementFactory.getRuntimeMXBean().getUptime() / 1000L));
    this.builtin("memory_used", "heap used (MB)", p ->
        String.valueOf((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
    this.builtin("memory_max", "max heap (MB)", p -> String.valueOf(Runtime.getRuntime().maxMemory() >> 20));
    this.builtin("memory_free", "free heap (MB)", p -> String.valueOf((Runtime.getRuntime().maxMemory()
        - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())) >> 20));
    this.builtin("memory_percent", "heap used (%)", p -> String.valueOf(Math.round(100.0
        * (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / Runtime.getRuntime().maxMemory())));
    this.builtin("cpu_cores", "CPU cores", p -> String.valueOf(Runtime.getRuntime().availableProcessors()));
    this.builtin("cpu_load", "proxy process CPU (%)", p -> {
      if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
        return String.valueOf(Math.max(0, Math.round(os.getProcessCpuLoad() * 100)));
      }
      return "?";
    });
    this.builtin("java_version", "Java version", p -> System.getProperty("java.version"));
    this.builtin("os", "operating system", p -> System.getProperty("os.name"));
    this.builtin("plugin_count", "loaded plugins", p -> String.valueOf(server.getPluginManager().getPlugins().size()));
    this.builtin("most_popular_server", "backend with the most players", p -> server.getAllServers().stream()
        .max(Comparator.comparingInt(s -> s.getPlayersConnected().size()))
        .map(s -> s.getServerInfo().getName()).orElse("-"));

    // ---- per backend server: {xxx_<server>} ----
    this.builtinPrefix("online_", "players on <server> (via this proxy)", (p, name) ->
        server.getServer(name).map(s -> String.valueOf(s.getPlayersConnected().size())).orElse("0"));
    this.builtinPrefix("players_", "names of players on <server>", (p, name) ->
        server.getServer(name).map(s -> s.getPlayersConnected().stream().map(Player::getUsername).sorted()
            .collect(Collectors.joining(", "))).orElse(""));
    this.builtinPrefix("status_", "online or offline (pinged)", (p, name) -> status.isOnline(name) ? "online" : "offline");
    this.builtinPrefix("status_color_", "<green> or <red> tag for <server>", (p, name) -> status.isOnline(name) ? "green" : "red");
    this.builtinPrefix("max_", "max players reported by <server>", (p, name) -> status.max(name));
    this.builtinPrefix("motd_", "MOTD of <server> (plain text)", (p, name) -> status.motd(name));
    this.builtinPrefix("version_", "version name of <server>", (p, name) -> status.version(name));
    this.builtinPrefix("latency_", "ping to <server> in ms", (p, name) -> status.latency(name));
    this.builtinPrefix("address_", "host:port of <server>", (p, name) ->
        server.getServer(name).map(s -> s.getServerInfo().getAddress().getHostString() + ":" + s.getServerInfo().getAddress().getPort()).orElse("-"));

    // ---- viewing player ----
    this.builtin("player", "player name", p -> p == null ? "" : p.getUsername());
    this.builtin("player_uuid", "player UUID", p -> p == null ? "" : p.getUniqueId().toString());
    this.builtin("player_ping", "player ping (ms)", p -> p == null ? "0" : String.valueOf(p.getPing()));
    this.builtin("player_server", "player's current server", p -> server(p).map(c -> c.getServerInfo().getName()).orElse("-"));
    this.builtin("player_server_online", "players on the player's server", p ->
        server(p).map(c -> String.valueOf(c.getServer().getPlayersConnected().size())).orElse("0"));
    this.builtin("player_server_max", "max players of the player's server", p ->
        server(p).map(c -> status.max(c.getServerInfo().getName())).orElse("0"));
    this.builtin("player_server_motd", "MOTD of the player's server", p -> server(p).map(c -> status.motd(c.getServerInfo().getName())).orElse(""));
    this.builtin("player_locale", "player locale, e.g. ja_jp", p -> locale(p).toString().toLowerCase(Locale.ROOT));
    this.builtin("player_language", "player language name", p -> locale(p).getDisplayLanguage(locale(p)));
    this.builtin("player_client_brand", "client brand, e.g. vanilla, fabric", p ->
        p == null || p.getClientBrand() == null ? "unknown" : p.getClientBrand());
    this.builtin("player_protocol", "protocol number", p -> p == null ? "0" : String.valueOf(p.getProtocolVersion().getProtocol()));
    this.builtin("player_version", "Minecraft version of the client", p -> p == null ? "" : p.getProtocolVersion().getMostRecentSupportedVersion());
    this.builtin("player_virtual_host", "host name the player connected with", p ->
        p == null ? "" : p.getVirtualHost().map(h -> h.getHostString()).orElse(""));
    this.builtin("player_online_mode", "true if the account is verified", p -> p == null ? "false" : String.valueOf(p.isOnlineMode()));
    this.builtin("player_session", "time since the player joined", p -> {
      final Long since = p == null ? null : loginTimes.get(p.getUniqueId());
      return since == null ? "0m" : formatDuration(Duration.ofMillis(System.currentTimeMillis() - since));
    });
    this.builtin("player_session_minutes", "minutes since the player joined", p -> {
      final Long since = p == null ? null : loginTimes.get(p.getUniqueId());
      return since == null ? "0" : String.valueOf((System.currentTimeMillis() - since) / 60_000L);
    });
    this.builtin("player_ping_color", "green/yellow/red tag by ping", p -> {
      final long ping = p == null ? 0 : p.getPing();
      return ping < 80 ? "green" : ping < 200 ? "yellow" : "red";
    });
  }

  private static Optional<ServerConnection> server(final @Nullable Player player) {
    return player == null ? Optional.empty() : player.getCurrentServer();
  }

  private static Locale locale(final @Nullable Player player) {
    if (player == null) {
      return Locale.ENGLISH;
    }
    final Locale effective = player.getEffectiveLocale();
    return effective != null ? effective : player.getPlayerSettings().getLocale();
  }

  static String formatDuration(final Duration duration) {
    final long days = duration.toDays();
    final long hours = duration.toHoursPart();
    final long minutes = duration.toMinutesPart();
    if (days > 0) {
      return days + "d " + hours + "h " + minutes + "m";
    }
    if (hours > 0) {
      return hours + "h " + minutes + "m";
    }
    return minutes + "m";
  }

  private void builtin(final String key, final String description, final Function<@Nullable Player, String> resolver) {
    this.exact.put(key, resolver);
    this.descriptions.put(key, description);
  }

  private void builtinPrefix(final String prefix, final String description, final BiFunction<@Nullable Player, String, String> resolver) {
    this.prefixes.put(prefix, resolver);
    this.descriptions.put(prefix + "<server>", description);
  }

  @Override
  public void register(final String key, final Function<@Nullable Player, String> resolver) {
    this.exact.put(key, resolver);
    this.descriptions.put(key, "(plugin)");
  }

  @Override
  public void registerPrefix(final String prefix, final BiFunction<@Nullable Player, String, String> resolver) {
    this.prefixes.put(prefix, resolver);
    this.descriptions.put(prefix + "<argument>", "(plugin)");
  }

  @Override
  public void unregister(final String keyOrPrefix) {
    this.exact.remove(keyOrPrefix);
    this.prefixes.remove(keyOrPrefix);
    this.descriptions.keySet().removeIf(k -> k.equals(keyOrPrefix) || k.startsWith(keyOrPrefix + "<"));
  }

  @Override
  public String apply(final String text, final @Nullable Player player) {
    return this.replace(text, player, false);
  }

  @Override
  public Component render(final String miniMessage, final @Nullable Player player) {
    return MiniMessage.miniMessage().deserialize(this.replace(miniMessage, player, true));
  }

  /** Like {@link #render}, with extra one-off values such as {@code previous_server}. */
  Component render(final String miniMessage, final @Nullable Player player, final Map<String, String> extra) {
    String text = miniMessage;
    for (final Map.Entry<String, String> entry : extra.entrySet()) {
      text = text.replace("{" + entry.getKey() + "}", MiniMessage.miniMessage().escapeTags(entry.getValue()));
    }
    return this.render(text, player);
  }

  private String replace(final String text, final @Nullable Player player, final boolean escape) {
    if (text.indexOf('{') < 0) {
      return text;
    }
    final StringBuilder out = new StringBuilder(text.length() + 32);
    int i = 0;
    while (i < text.length()) {
      final char c = text.charAt(i);
      final int end = c == '{' ? text.indexOf('}', i + 1) : -1;
      if (end > i + 1) {
        final String key = text.substring(i + 1, end);
        final String value = this.resolve(key, player);
        if (value != null) {
          out.append(escape && !key.endsWith("color") && !key.startsWith("status_color_") ? MiniMessage.miniMessage().escapeTags(value) : value);
          i = end + 1;
          continue;
        }
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  private @Nullable String resolve(final String key, final @Nullable Player player) {
    try {
      final Function<@Nullable Player, String> exactResolver = this.exact.get(key);
      if (exactResolver != null) {
        return exactResolver.apply(player);
      }
      String bestPrefix = null;
      for (final String prefix : this.prefixes.keySet()) {
        if (key.startsWith(prefix) && key.length() > prefix.length() && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
          bestPrefix = prefix;
        }
      }
      return bestPrefix == null ? null : this.prefixes.get(bestPrefix).apply(player, key.substring(bestPrefix.length()));
    } catch (final RuntimeException ex) {
      return "?";
    }
  }

  @Override
  public Map<String, String> describe() {
    final Map<String, String> sorted = new LinkedHashMap<>();
    this.descriptions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> sorted.put(e.getKey(), e.getValue()));
    return sorted;
  }

  /** Current value of every exact placeholder for {@code player}, for the list command. */
  Map<String, String> values(final @Nullable Player player) {
    final Map<String, String> values = new LinkedHashMap<>();
    this.exact.keySet().stream().sorted().forEach(k -> values.put(k, String.valueOf(this.resolve(k, player))));
    return values;
  }

  /** Server names, for examples in the list command. */
  static String firstServer(final VelocityServer server) {
    return server.getAllServers().stream().map(RegisteredServer::getServerInfo).map(i -> i.getName()).sorted().findFirst().orElse("lobby");
  }
}
