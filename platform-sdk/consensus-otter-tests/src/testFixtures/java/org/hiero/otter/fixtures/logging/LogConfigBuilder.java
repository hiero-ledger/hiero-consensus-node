// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.appender.ConsoleAppender.Target;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.FilterComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * Builds and installs a unified Log4j2 configuration shared by both the Turtle- and
 * Container-based environments.
 * <p>
 * The configuration is created programmatically (no XML) and follows this guide:
 * <ul>
 *     <li>Two log files per node (<code>swirlds.log</code> and <code>swirlds-hashstream.log</code>)</li>
 *     <li>Console output that mirrors <code>swirlds.log</code></li>
 *     <li>Optional per-node routing via {@link ThreadContext}</li>
 *     <li>In-memory appender for tests</li>
 * </ul>
 */
public final class LogConfigBuilder {

    /** Markers that are allowed in swirlds.log & console. */
    private static final Set<String> ALLOWED_MARKERS = Set.of(
            "EXCEPTION",
            "TESTING_EXCEPTIONS",
            "SOCKET_EXCEPTIONS",
            "INVALID_EVENT_ERROR",
            "JVM_PAUSE_ERROR",
            "THREADS",
            "EVENT_PARSER",
            "STARTUP",
            "PLATFORM_STATUS",
            "RECONNECT",
            "FREEZE",
            "SNAPSHOT_MANAGER",
            "STATE_TO_DISK",
            "MIGRATION",
            "DEMO_INFO",
            "DEMO_QUORUM",
            "TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT");

    private static final String HASH_STREAM_MARKER = "STATE_HASH";

    /** Default pattern for text-based appenders. */
    private static final String DEFAULT_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %notEmpty{[%marker] }%-5level %logger{36} - %msg %n";

    /** Pattern used when JVM thread-local context is used to separate nodes */
    private static final String NODE_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [nodeId-%X{nodeId}] [%t] %notEmpty{[%marker] }%-5level %logger{36} - %msg %n";

    private LogConfigBuilder() {
        // utility
    }

    /**
     * Installs a new Log4j2 configuration that logs into the given <code>logDir</code>.
     * The configuration is <em>global</em> (i.e. affects the entire JVM).
     *
     * @param logDir     directory where log files are written (created automatically)
     */
    public static void configure(final Path logDir) {
        configure(logDir, Collections.emptyMap());
    }

    /**
     * Installs a new Log4j2 configuration that logs into the given directories. The map argument
     * allows callers to specify per-node output directories.
     * For all nodes contained in the map an individual set of appenders is created. If the map is
     * empty a single global set of appenders is produced.
     *
     * @param defaultLogDir directory used when no per-node mapping is provided
     * @param nodeLogDirs   mapping (node-ID  ➔  directory) for per-node log routing
     */
    public static void configure(final Path defaultLogDir, final Map<NodeId, Path> nodeLogDirs) {
        requireNonNull(defaultLogDir, "defaultLogDir must not be null");
        final Map<NodeId, Path> safeCopy = nodeLogDirs == null ? Map.of() : new ConcurrentHashMap<>(nodeLogDirs);

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();

        final LayoutComponentBuilder standardLayout =
                builder.newLayout("PatternLayout").addAttribute("pattern", DEFAULT_PATTERN);
        final LayoutComponentBuilder nodeLayout =
                builder.newLayout("PatternLayout").addAttribute("pattern", NODE_PATTERN);

        final FilterComponentBuilder thresholdInfoFilter = builder.newFilter(
                        "ThresholdFilter", Result.NEUTRAL, Result.DENY)
                .addAttribute("level", Level.INFO);

        final ComponentBuilder<?> allowedMarkerFilters = builder.newComponent("filters");
        for (final String marker : ALLOWED_MARKERS) {
            allowedMarkerFilters.addComponent(builder.newFilter("MarkerFilter", Result.ACCEPT, Result.NEUTRAL)
                    .addAttribute("marker", marker));
        }
        // deny everything else
        allowedMarkerFilters.addComponent(builder.newFilter("DenyAllFilter", Result.DENY, Result.DENY));

        final ComponentBuilder<?> markersAndThreshold = builder.newComponent("filters")
                .addComponent(thresholdInfoFilter)
                .addComponent(allowedMarkerFilters);

        // Filter for STATE_HASH only (hash-stream file)
        final ComponentBuilder<?> hashStreamFilter = builder.newComponent("filters")
                .addComponent(thresholdInfoFilter)
                .addComponent(builder.newFilter("MarkerFilter", Result.ACCEPT, Result.DENY)
                        .addAttribute("marker", HASH_STREAM_MARKER));

        final Map<String, String> createdFileAppenderNames = new ConcurrentHashMap<>();
        final Map<String, String> createdHashAppenderNames = new ConcurrentHashMap<>();

        final List<Map.Entry<NodeId, Path>> sources;
        if (safeCopy.isEmpty()) {
            // single JVM-wide configuration
            sources = List.of();
            final String fileAppender = addFileAppender(
                    builder,
                    "FileLogger",
                    standardLayout,
                    markersAndThreshold,
                    defaultLogDir.resolve("swirlds.log").toString());
            final String hashAppender = addFileAppender(
                    builder,
                    "HashStreamLogger",
                    standardLayout,
                    hashStreamFilter,
                    defaultLogDir.resolve("swirlds-hashstream.log").toString());
            createdFileAppenderNames.put("GLOBAL", fileAppender);
            createdHashAppenderNames.put("GLOBAL", hashAppender);
        } else {
            sources = safeCopy.entrySet().stream().toList();
            // Per node appenders
            for (final Map.Entry<NodeId, Path> entry : sources) {
                final String nodeIdString = Long.toString(entry.getKey().id());
                final String fileAppenderName = "FileLogger-" + nodeIdString;
                final String hashAppenderName = "HashStreamLogger-" + nodeIdString;

                addFileAppender(
                        builder,
                        fileAppenderName,
                        standardLayout,
                        markersAndThreshold,
                        entry.getValue()
                                .resolve("swirlds-" + nodeIdString + ".log")
                                .toString());
                addFileAppender(
                        builder,
                        hashAppenderName,
                        standardLayout,
                        hashStreamFilter,
                        entry.getValue()
                                .resolve("swirlds-hashstream-" + nodeIdString + ".log")
                                .toString());

                createdFileAppenderNames.put(nodeIdString, fileAppenderName);
                createdHashAppenderNames.put(nodeIdString, hashAppenderName);
            }
        }

        // Console appender (mirrors swirlds.log behaviour)
        final AppenderComponentBuilder consoleAppender = builder.newAppender("ConsoleMarker", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(safeCopy.isEmpty() ? standardLayout : nodeLayout)
                .addComponent(markersAndThreshold);
        builder.add(consoleAppender);

        // In-memory appender for tests
        builder.add(builder.newAppender("InMemory", "InMemoryAppender"));

        final RootLoggerComponentBuilder root = builder.newRootLogger(Level.ALL)
                .add(builder.newAppenderRef("ConsoleMarker"))
                .add(builder.newAppenderRef("InMemory"));

        // Attach file appenders
        for (final String appender : createdFileAppenderNames.values()) {
            root.add(builder.newAppenderRef(appender));
        }
        for (final String appender : createdHashAppenderNames.values()) {
            root.add(builder.newAppenderRef(appender));
        }
        builder.add(root);

        // Separate DEBUG console appender for the otter package (unfiltered)
        final AppenderComponentBuilder consoleDebugAppender = builder.newAppender("ConsoleDebug", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(standardLayout)
                .addComponent(builder.newFilter("ThresholdFilter", Result.NEUTRAL, Result.DENY)
                        .addAttribute("level", Level.DEBUG));
        builder.add(consoleDebugAppender);
        builder.add(builder.newLogger("org.hiero.consensus.otter", Level.DEBUG)
                .add(builder.newAppenderRef("ConsoleDebug"))
                .addAttribute("additivity", false));

        Configurator.reconfigure(builder.build());

        LogManager.getLogger(LogConfigBuilder.class).info("Unified logging configuration (re)initialized");
    }

    /**
     * Helper method that adds a file appender to the builder.
     *
     * @return the name of the created appender
     */
    private static String addFileAppender(
            final ConfigurationBuilder<BuiltConfiguration> builder,
            final String name,
            final LayoutComponentBuilder layout,
            final ComponentBuilder<?> filters,
            final String fileName) {
        final AppenderComponentBuilder appender = builder.newAppender(name, "File")
                .addAttribute("fileName", fileName)
                .addAttribute("append", true)
                .add(layout)
                .addComponent(filters);
        builder.add(appender);
        return name;
    }
}
