/*
 * Copyright (C) 2019-2023 Velocity Contributors
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

package com.velocitypowered.proxy;

import com.velocitypowered.proxy.config.VelocityConfiguration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bstats.MetricsBase;
import org.bstats.charts.CustomChart;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bstats.config.MetricsConfig;
import org.bstats.json.JsonObjectBuilder;

/**
 * Initializes bStats.
 */
public class Metrics {

  private MetricsBase metricsBase;

  private final VelocityServer server;

  private Metrics(Logger logger, VelocityServer server, int serviceId, boolean defaultEnabled) {
    this.server = server;
    File configFile = Path.of("plugins", "bStats", "config.txt").toFile();
    MetricsConfig config;
    try {
      config = new MetricsConfig(configFile, defaultEnabled);
    } catch (IOException e) {
      logger.error("Failed to create bStats config", e);
      return;
    }

    metricsBase = new MetricsBase(
        "velocity", // Storia - Storia Proxy's own bStats page
        config.getServerUUID(),
        serviceId,
        config.isEnabled(),
        this::appendPlatformData,
        builder -> builder.appendField("pluginVersion", server.getVersion().getVersion()),
        null,
        () -> true,
        logger::warn,
        logger::info,
        config.isLogErrorsEnabled(),
        config.isLogSentDataEnabled(),
        config.isLogResponseStatusTextEnabled(),
        false
    );

    if (!config.didExistBefore()) {
      // Send an info message when the bStats config file gets created for the first time
      logger.info("Velocity and some of its plugins collect metrics"
          + " and send them to bStats (https://bStats.org).");
      logger.info("bStats collects some basic information for plugin"
          + " authors, like how many people use");
      logger.info("their plugin and their total player count."
          + " It's recommended to keep bStats enabled, but");
      logger.info("if you're not comfortable with this, you can opt-out"
          + " by editing the config.txt file in");
      logger.info("the '/plugins/bStats/' folder and setting enabled to false.");
    }
  }

  /**
   * Adds a custom chart.
   *
   * @param chart The chart to add.
   */
  public void addCustomChart(CustomChart chart) {
    metricsBase.addCustomChart(chart);
  }

  private void appendPlatformData(JsonObjectBuilder builder) {
    // Storia start - the fields bStats expects from Velocity
    builder.appendField("playerAmount", server.getPlayerCount());
    builder.appendField("managedServers", server.getAllServers().size());
    builder.appendField("onlineMode", server.getConfiguration().isOnlineMode() ? 1 : 0);
    builder.appendField("velocityVersionVersion", server.getVersion().getVersion());
    builder.appendField("velocityVersionName", server.getVersion().getName());
    builder.appendField("velocityVersionVendor", server.getVersion().getVendor());
    builder.appendField("javaVersion", System.getProperty("java.version"));
    // Storia end
    builder.appendField("osName", System.getProperty("os.name"));
    builder.appendField("osArch", System.getProperty("os.arch"));
    builder.appendField("osVersion", System.getProperty("os.version"));
    builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
  }

  static class VelocityMetrics {

    private static final Logger logger = LogManager.getLogger(Metrics.class);

    static void startMetrics(VelocityServer server, VelocityConfiguration.Metrics metricsConfig) {
      Metrics metrics = new Metrics(logger, server, 34365, metricsConfig.isEnabled()); // Storia - https://bstats.org/plugin/velocity/StoriaProxy/34365

      metrics.addCustomChart(
          new SingleLineChart("players", server::getPlayerCount)
      );
      metrics.addCustomChart(
          new SingleLineChart("managed_servers", () -> server.getAllServers().size())
      );
      metrics.addCustomChart(
          new SimplePie("online_mode",
              () -> server.getConfiguration().isOnlineMode() ? "online" : "offline")
      );
      metrics.addCustomChart(new SimplePie("velocity_version",
          () -> server.getVersion().getVersion()));

      metrics.addCustomChart(new DrilldownPie("java_version", () -> {
        Runtime.Version version = Runtime.version();

        return Map.of(
            "Java " + version.feature(),
            Map.of(javaVersion(version), 1));
      }));
    }
  }

  /**
   * Recreates the exact {@code java.version} system property value from a {@link Runtime.Version}.
   *
   * <p>Per <a href="https://openjdk.org/jeps/223">JEP 223</a>, {@code java.version} is
   * {@code $VNUM(-$PRE)?}; the build and optional segments only appear in {@code java.runtime.version}.
   *
   * @param v the runtime version
   * @return the value {@code java.version} would hold on this JVM
   */
  private static String javaVersion(Runtime.Version v) {
    return v.version().stream()
        .map(Object::toString)
        .collect(Collectors.joining("."))
        + v.pre().map(p -> "-" + p).orElse("");
  }
}
