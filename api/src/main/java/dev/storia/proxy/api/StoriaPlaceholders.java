/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package dev.storia.proxy.api;

import com.velocitypowered.api.proxy.Player;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Placeholders built into Storia Proxy, usable in its MOTD, tab list and messages, and by plugins.
 *
 * <p>A placeholder is written {@code {name}}. Built-in placeholders cover the proxy
 * ({@code {online}}, {@code {uptime}}, ...), each backend server ({@code {online_lobby}},
 * {@code {status_lobby}}, ...) and the viewing player ({@code {player}}, {@code {player_ping}}, ...);
 * {@code /storiaproxy placeholders} lists them all with their current values.
 *
 * <p>Plugins can add their own:
 * <pre>{@code
 * StoriaPlaceholders.get().register("coins", player -> player == null ? "0" : coins(player));
 * StoriaPlaceholders.get().registerPrefix("team_", (player, arg) -> teamSize(arg));   // {team_red}
 * Component c = StoriaPlaceholders.get().render("<gold>{coins} coins</gold>", player);
 * }</pre>
 */
public interface StoriaPlaceholders {

  /**
   * Returns the placeholder registry of the running Storia Proxy.
   *
   * @return the registry
   * @throws IllegalStateException if called before the proxy has started
   */
  static StoriaPlaceholders get() {
    return Objects.requireNonNull(Holder.instance, "Storia Proxy has not started yet");
  }

  /**
   * Registers {@code {key}}. The resolver gets the viewing player, or {@code null} when there is none
   * (for example in the server list MOTD), and returns plain text (MiniMessage tags are escaped).
   *
   * @param key the placeholder name, without braces
   * @param resolver produces the value
   */
  void register(String key, Function<@Nullable Player, String> resolver);

  /**
   * Registers every {@code {prefix<argument>}}, for example {@code registerPrefix("team_", ...)} handles
   * {@code {team_red}} with argument {@code red}.
   *
   * @param prefix the placeholder prefix, without braces
   * @param resolver produces the value from the player and the argument
   */
  void registerPrefix(String prefix, BiFunction<@Nullable Player, String, String> resolver);

  /**
   * Removes a placeholder or prefix registered by {@link #register} or {@link #registerPrefix}.
   *
   * @param keyOrPrefix the name or prefix
   */
  void unregister(String keyOrPrefix);

  /**
   * Replaces all known placeholders in {@code text}. Unknown ones are left as they are.
   *
   * @param text the text
   * @param player the viewing player, or null
   * @return the text with placeholders replaced
   */
  String apply(String text, @Nullable Player player);

  /**
   * Replaces placeholders, then parses the result as MiniMessage.
   *
   * @param miniMessage the MiniMessage text
   * @param player the viewing player, or null
   * @return the component
   */
  Component render(String miniMessage, @Nullable Player player);

  /**
   * Every placeholder with a short description, for help screens.
   *
   * @return placeholder names (prefixes end with {@code <argument>}) mapped to descriptions
   */
  Map<String, String> describe();

  /**
   * Holds the running registry.
   */
  final class Holder {
    private static volatile @Nullable StoriaPlaceholders instance;

    private Holder() {
    }

    /**
     * Sets the running registry. Called by Storia Proxy on startup.
     *
     * @param placeholders the registry
     */
    public static void set(StoriaPlaceholders placeholders) {
      instance = placeholders;
    }
  }
}
