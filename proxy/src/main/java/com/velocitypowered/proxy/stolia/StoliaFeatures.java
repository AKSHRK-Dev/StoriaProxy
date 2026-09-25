/*
 * Copyright (C) 2026 Stolia Contributors
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

package com.velocitypowered.proxy.stolia;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import dev.stolia.proxy.api.StoliaPlaceholders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Stolia Proxy features built on the placeholders: server list MOTD, tab list header/footer,
 * join/leave/switch messages and the {@code /stoliaproxy} command. Configured in {@code stolia-proxy.toml}.
 */
public final class StoliaFeatures {

  private static final Logger LOGGER = LogManager.getLogger(StoliaFeatures.class);
  private static final String DEFAULT_CONFIG = """
      # Stolia Proxy settings. Text is MiniMessage (https://docs.advntr.dev/minimessage/format)
      # with {placeholders}. Run /stoliaproxy placeholders in game or in the console to list them all
      # with their current values, and /stoliaproxy parse <text> to try a line.

      [motd]
      # Replaces the server list description (two lines).
      enabled = true
      lines = [
        "<gradient:#bdbdbd:#ffffff><bold>{proxy_name}</bold></gradient> <dark_gray>|</dark_gray> <gray>{servers_online}/{server_count} servers up",
        "<gray>{online} players online <dark_gray>·</dark_gray> {time}"
      ]
      # Max players shown in the server list; -1 keeps show-max-players from velocity.toml.
      max-players = -1

      [tablist]
      enabled = true
      # How often headers and footers are refreshed, in milliseconds.
      interval-ms = 1000
      header = [
        "",
        "<white><bold>{proxy_name}</bold>",
        "<gray>{online}/{max} online <dark_gray>·</dark_gray> {time}",
        ""
      ]
      footer = [
        "",
        "<gray>{player_server} <dark_gray>({player_server_online})</dark_gray> <dark_gray>·</dark_gray> ping <{player_ping_color}>{player_ping}ms</{player_ping_color}>",
        "<dark_gray>uptime {uptime}",
        ""
      ]

      [messages]
      # Broadcast to everyone. Leave empty ("") to disable. Placeholders refer to the player the message is about.
      join = "<dark_gray>[<green>+</green>]</dark_gray> <gray>{player}"
      leave = "<dark_gray>[<red>-</red>]</dark_gray> <gray>{player}"
      # Also has {previous_server}.
      switch = "<dark_gray>[<aqua>»</aqua>]</dark_gray> <gray>{player}: {previous_server} → {player_server}"

      [server-status]
      # How often backend servers are pinged for {status_<server>}, {motd_<server>} and friends.
      refresh-seconds = 10
      """;

  private final VelocityServer server;
  private final Path file;
  private final Map<UUID, Long> loginTimes = new ConcurrentHashMap<>();
  private final ServerStatusCache status;
  private final PlaceholderRegistry placeholders;
  private volatile Settings settings;
  private @Nullable ScheduledTask tabTask;
  private @Nullable ScheduledTask statusTask;

  private record Settings(boolean motd, List<String> motdLines, int maxPlayers,
                          boolean tablist, long tabInterval, List<String> header, List<String> footer,
                          String join, String leave, String switchMessage, long statusRefresh) {}

  /**
   * Creates the features and registers the placeholders, listeners and command.
   *
   * @param server the proxy
   */
  public StoliaFeatures(final VelocityServer server) {
    this.server = server;
    this.file = Path.of("stolia-proxy.toml");
    this.status = new ServerStatusCache(server);
    this.placeholders = new PlaceholderRegistry(server, this.status, this.loginTimes);
    StoliaPlaceholders.Holder.set(this.placeholders);
    this.settings = this.load();
    server.getEventManager().register(VelocityVirtualPlugin.INSTANCE, this);
    this.registerCommand();
    this.schedule();
  }

  private Settings load() {
    try {
      if (!Files.exists(this.file)) {
        Files.writeString(this.file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
      }
    } catch (final IOException ex) {
      LOGGER.warn("Could not write {}", this.file, ex);
    }
    try (CommentedFileConfig config = CommentedFileConfig.builder(this.file).build()) {
      config.load();
      return new Settings(
          config.getOrElse("motd.enabled", true),
          config.getOrElse("motd.lines", List.of("{proxy_name}", "{online} online")),
          config.<Number>getOrElse("motd.max-players", -1).intValue(),
          config.getOrElse("tablist.enabled", true),
          Math.max(250L, config.<Number>getOrElse("tablist.interval-ms", 1000).longValue()),
          config.getOrElse("tablist.header", List.of()),
          config.getOrElse("tablist.footer", List.of()),
          config.getOrElse("messages.join", ""),
          config.getOrElse("messages.leave", ""),
          config.getOrElse("messages.switch", ""),
          Math.max(2L, config.<Number>getOrElse("server-status.refresh-seconds", 10).longValue()));
    } catch (final RuntimeException ex) {
      LOGGER.error("Could not read {}; keeping the previous Stolia Proxy settings", this.file, ex);
      return this.settings != null ? this.settings : new Settings(true, List.of("{proxy_name}", "{online} online"), -1,
          false, 1000, List.of(), List.of(), "", "", "", 10);
    }
  }

  private void schedule() {
    if (this.tabTask != null) {
      this.tabTask.cancel();
    }
    if (this.statusTask != null) {
      this.statusTask.cancel();
    }
    final Settings current = this.settings;
    this.statusTask = this.server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this.status::refresh)
        .repeat(current.statusRefresh(), TimeUnit.SECONDS).schedule();
    if (current.tablist()) {
      this.tabTask = this.server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::updateTabLists)
          .repeat(current.tabInterval(), TimeUnit.MILLISECONDS).schedule();
    }
  }

  private void updateTabLists() {
    final Settings current = this.settings;
    for (final Player player : this.server.getAllPlayers()) {
      player.getTabList().setHeaderAndFooter(
          this.placeholders.render(String.join("<newline>", current.header()), player),
          this.placeholders.render(String.join("<newline>", current.footer()), player));
    }
  }

  // ---- events -----------------------------------------------------------------------------------

  /**
   * Server list MOTD.
   *
   * @param event the ping
   */
  @Subscribe
  public void onPing(final ProxyPingEvent event) {
    final Settings current = this.settings;
    if (!current.motd()) {
      return;
    }
    final ServerPing.Builder builder = event.getPing().asBuilder()
        .description(this.placeholders.render(String.join("<newline>", current.motdLines()), null));
    if (current.maxPlayers() >= 0) {
      builder.maximumPlayers(current.maxPlayers());
    }
    event.setPing(builder.build());
  }

  /**
   * Join message and session start.
   *
   * @param event the login
   */
  @Subscribe
  public void onLogin(final PostLoginEvent event) {
    this.loginTimes.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    this.broadcast(this.settings.join(), event.getPlayer(), Map.of());
  }

  /**
   * Leave message.
   *
   * @param event the disconnect
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    if (event.getLoginStatus() == DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
      this.broadcast(this.settings.leave(), event.getPlayer(), Map.of());
    }
    this.loginTimes.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Server switch message.
   *
   * @param event the switch
   */
  @Subscribe
  public void onSwitch(final ServerConnectedEvent event) {
    event.getPreviousServer().ifPresent(previous ->
        this.server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, () ->
            this.broadcast(this.settings.switchMessage(), event.getPlayer(), Map.of("previous_server", previous.getServerInfo().getName())))
            .delay(250, TimeUnit.MILLISECONDS).schedule());
  }

  private void broadcast(final String template, final Player subject, final Map<String, String> extra) {
    if (template == null || template.isBlank()) {
      return;
    }
    final Component message = this.placeholders.render(template, subject, extra);
    for (final Player player : this.server.getAllPlayers()) {
      player.sendMessage(message);
    }
    this.server.getConsoleCommandSource().sendMessage(message);
  }

  // ---- command ----------------------------------------------------------------------------------

  private void registerCommand() {
    final LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("stoliaproxy")
        .requires(source -> source.hasPermission("stoliaproxy.admin"))
        .executes(ctx -> {
          ctx.getSource().sendMessage(Component.text("/stoliaproxy placeholders | parse <text> | reload", NamedTextColor.YELLOW));
          return 1;
        })
        .then(BrigadierCommand.literalArgumentBuilder("placeholders").executes(ctx -> {
          final Player player = ctx.getSource() instanceof Player p ? p : null;
          final CommandSource source = ctx.getSource();
          source.sendMessage(Component.text("Placeholders (write them as {name}):", NamedTextColor.AQUA));
          final Map<String, String> values = this.placeholders.values(player);
          this.placeholders.describe().forEach((name, description) -> {
            final String value = values.containsKey(name) ? " = " + values.get(name) : "";
            source.sendMessage(Component.text(" {" + name + "}", NamedTextColor.WHITE)
                .append(Component.text(value, NamedTextColor.GREEN))
                .append(Component.text("  " + description, NamedTextColor.GRAY)));
          });
          source.sendMessage(Component.text("<server> is a backend name, e.g. {online_" + PlaceholderRegistry.firstServer(this.server) + "}",
              NamedTextColor.GRAY));
          return 1;
        }))
        .then(BrigadierCommand.literalArgumentBuilder("parse")
            .then(RequiredArgumentBuilder.<CommandSource, String>argument("text", StringArgumentType.greedyString()).executes(ctx -> {
              final Player player = ctx.getSource() instanceof Player p ? p : null;
              ctx.getSource().sendMessage(this.placeholders.render(StringArgumentType.getString(ctx, "text"), player));
              return 1;
            })))
        .then(BrigadierCommand.literalArgumentBuilder("reload").executes(ctx -> {
          this.settings = this.load();
          this.schedule();
          ctx.getSource().sendMessage(Component.text("Reloaded stolia-proxy.toml", NamedTextColor.GREEN));
          return 1;
        }));
    final BrigadierCommand command = new BrigadierCommand(root);
    this.server.getCommandManager().register(
        this.server.getCommandManager().metaBuilder(command).plugin(VelocityVirtualPlugin.INSTANCE).build(), command);
  }
}
