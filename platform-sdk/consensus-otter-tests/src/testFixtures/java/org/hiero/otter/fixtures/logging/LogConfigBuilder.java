// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.FREEZE;
import static com.swirlds.logging.legacy.LogMarker.INVALID_EVENT_ERROR;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.THREADS;
import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.apache.logging.log4j.core.config.builder.api.KeyValuePairComponentBuilder;
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
    private static final Set<LogMarker> ALLOWED_MARKERS = Set.of(
            EXCEPTION,
            TESTING_EXCEPTIONS,
            SOCKET_EXCEPTIONS,
            INVALID_EVENT_ERROR,
            THREADS,
            STARTUP,
            PLATFORM_STATUS,
            RECONNECT,
            FREEZE,
            STATE_TO_DISK,
            DEMO_INFO,
            TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT);

    /** Ignoring marker used from threads without correct ThreadContext */
    private static final Set<LogMarker> IGNORED_MARKERS = Set.of(STARTUP, MERKLE_DB, VIRTUAL_MERKLE_STATS);

    /** Default pattern for text-based appenders. */
    private static final String DEFAULT_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %notEmpty{[%marker] }%-5level %logger{36} - %msg %n";

    private LogConfigBuilder() {
        // utility
    }

    /**
     * Installs a new Log4j2 configuration that logs into the given <code>logDir</code>.
     * The configuration is <em>global</em> (i.e. affects the entire JVM).
     *
     * @param baseDir     directory where log files are written (created automatically)
     */
    public static void configureNode(@NonNull final Path baseDir) {
        requireNonNull(baseDir, "baseDir must not be null");
        final Path defaultLogDir = baseDir.resolve("output");

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        final LayoutComponentBuilder standardLayout =
                builder.newLayout("PatternLayout").addAttribute("pattern", DEFAULT_PATTERN);

        final FilterComponentBuilder thresholdInfoFilter = createThresholdFilter(builder);
        final ComponentBuilder<?> allowedMarkerFilters = createAllowedMarkerFilters(builder);
        final ComponentBuilder<?> hashStreamFilter = configureHashStreamFilter(builder);

        // single JVM-wide configuration
        final AppenderComponentBuilder fileAppender = createFileAppender(
                builder,
                "FileLogger",
                standardLayout,
                defaultLogDir.resolve("swirlds.log").toString(),
                thresholdInfoFilter,
                allowedMarkerFilters);
        builder.add(fileAppender);

        final AppenderComponentBuilder hashAppender = createFileAppender(
                builder,
                "HashStreamLogger",
                standardLayout,
                defaultLogDir
                        .resolve("swirlds-hashstream/swirlds-hashstream.log")
                        .toString(),
                hashStreamFilter);
        builder.add(hashAppender);

        final AppenderComponentBuilder consoleAppender = builder.newAppender("ConsoleLogger", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(standardLayout)
                .addComponent(combineFilters(builder, thresholdInfoFilter, allowedMarkerFilters));
        builder.add(consoleAppender);

        final RootLoggerComponentBuilder root = builder.newRootLogger(Level.ALL)
                .add(builder.newAppenderRef("InMemory"))
                .add(builder.newAppenderRef("FileLogger"))
                .add(builder.newAppenderRef("HashStreamLogger"))
                .add(builder.newAppenderRef("ConsoleLogger"));
        // Register the root logger with the configuration
        builder.add(root);

        Configurator.reconfigure(builder.build());

        LogManager.getLogger(LogConfigBuilder.class).info("Logging configuration (re)initialized");
    }

    /**
     * Installs a new Log4j2 configuration that logs into the given directories. The map argument
     * allows callers to specify per-node output directories.
     * For all nodes contained in the map an individual set of appenders is created.
     *
     * @param baseDir       directory used when no per-node mapping is provided
     * @param nodeLogDirs   mapping (node-ID  ➔  directory) for per-node log routing
     */
    public static void configureMultiNode(@NonNull final Path baseDir, @NonNull final Map<NodeId, Path> nodeLogDirs) {
        requireNonNull(baseDir, "baseDir must not be null");
        requireNonNull(nodeLogDirs, "nodeLogDirs must not be null");

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();

        final LayoutComponentBuilder standardLayout =
                builder.newLayout("PatternLayout").addAttribute("pattern", DEFAULT_PATTERN);

        final FilterComponentBuilder thresholdInfoFilter = createThresholdFilter(builder);
        final ComponentBuilder<?> allowedMarkerFilters = createAllowedMarkerFilters(builder);
        final ComponentBuilder<?> hashStreamFilter = configureHashStreamFilter(builder);

        final Map<String, String> createdFileAppenderNames = new HashMap<>();
        final Map<String, String> createdHashAppenderNames = new HashMap<>();
        final List<FilterComponentBuilder> excludeNodeFilters = new ArrayList<>();

        // Per node appenders
        for (final Map.Entry<NodeId, Path> entry : nodeLogDirs.entrySet()) {
            final String nodeId = Long.toString(entry.getKey().id());

            excludeNodeFilters.add(createExcludeNodeFilter(builder, entry.getKey()));
            final FilterComponentBuilder nodeOnlyFilter = createNodeOnlyFilter(builder, entry.getKey());

            final AppenderComponentBuilder fileAppender = createFileAppender(
                    builder,
                    "FileLogger-" + nodeId,
                    standardLayout,
                    entry.getValue().resolve("output/swirlds.log").toString(),
                    nodeOnlyFilter,
                    thresholdInfoFilter,
                    allowedMarkerFilters);

            builder.add(fileAppender);
            createdFileAppenderNames.put(nodeId, fileAppender.getName());

            final AppenderComponentBuilder hashAppender = createFileAppender(
                    builder,
                    "HashStreamLogger-" + nodeId,
                    standardLayout,
                    entry.getValue()
                            .resolve("output/swirlds-hashstream/swirlds-hashstream.log")
                            .toString(),
                    nodeOnlyFilter,
                    hashStreamFilter);

            builder.add(hashAppender);
            createdHashAppenderNames.put(nodeId, hashAppender.getName());
        }

        // Console logger
        final ComponentBuilder<?> excludeNodeFilter =
                combineFilters(builder, excludeNodeFilters.toArray(new FilterComponentBuilder[0]));

        final ComponentBuilder<?> consoleFilters =
                combineFilters(builder, thresholdInfoFilter, excludeNodeFilter, creatIgnoreMarkerFilters(builder));

        final AppenderComponentBuilder consoleAppender = builder.newAppender("Console", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(standardLayout)
                .addComponent(consoleFilters);

        builder.add(consoleAppender);

        // In-memory appender for tests
        builder.add(builder.newAppender("InMemory", "InMemoryAppender"));

        final RootLoggerComponentBuilder root =
                builder.newRootLogger(Level.ALL).add(builder.newAppenderRef("InMemory"));

        root.add(builder.newAppenderRef("Console"));

        // Attach file appenders
        for (final String appender : createdFileAppenderNames.values()) {
            root.add(builder.newAppenderRef(appender));
        }
        for (final String appender : createdHashAppenderNames.values()) {
            root.add(builder.newAppenderRef(appender));
        }

        // Register the root logger with the configuration
        builder.add(root);

        Configurator.reconfigure(builder.build());

        LogManager.getLogger(LogConfigBuilder.class).info("Unified logging configuration (re)initialized");
    }

    public static void configureTest() {
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();

        final LayoutComponentBuilder standardLayout =
                builder.newLayout("PatternLayout").addAttribute("pattern", DEFAULT_PATTERN);

        final FilterComponentBuilder thresholdInfoFilter = createThresholdFilter(builder);
        final AppenderComponentBuilder consoleAppender = builder.newAppender("Console", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(standardLayout)
                .addComponent(thresholdInfoFilter);
        builder.add(consoleAppender);

        final RootLoggerComponentBuilder root = builder.newRootLogger(Level.ALL).add(builder.newAppenderRef("Console"));

        builder.add(root);

        Configurator.reconfigure(builder.build());

        LogManager.getLogger(LogConfigBuilder.class).info("Test logging configuration (re)initialized");
    }

    /**
     * Creates a composite filter that only ACCEPTs log events tagged with {@link LogMarker#STATE_HASH}
     * and DENYs everything else.
     *
     * @param builder the Log4j2 {@link ConfigurationBuilder} used to assemble the configuration
     * @return the fully configured {@link ComponentBuilder} holding the filter chain
     */
    @NonNull
    private static ComponentBuilder<?> configureHashStreamFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        return builder.newComponent("Filters")
                .addComponent(builder.newFilter("MarkerFilter", Result.ACCEPT, Result.DENY)
                        .addAttribute("marker", STATE_HASH));
    }

    /**
     * Creates a filter that only allows log events originating from the given {@code nodeId} as
     * indicated by the {@code nodeId} entry in the {@link ThreadContext} map.
     *
     * @param builder the configuration builder
     * @param nodeId  the node that should be allowed
     * @return a filter that ACCEPTs events for the specified node and DENYs all others
     */
    @NonNull
    private static FilterComponentBuilder createNodeOnlyFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder, @NonNull final NodeId nodeId) {
        final KeyValuePairComponentBuilder keyValuePair = builder.newKeyValuePair("nodeId", nodeId.toString());

        return builder.newFilter("ThreadContextMapFilter", Result.NEUTRAL, Result.DENY)
                .addComponent(keyValuePair);
    }

    /**
     * Creates a filter that DENYs all log events coming from the specified {@code nodeId}. This is
     * the counterpart to {@link #createNodeOnlyFilter(ConfigurationBuilder, NodeId)} and is mainly
     * used for console output where we want to exclude per-node log lines when routing is enabled.
     *
     * @param builder the configuration builder
     * @param nodeId  the node that should be excluded
     * @return a filter that DENYs the specified node and NEUTRAL for all others
     */
    @NonNull
    private static FilterComponentBuilder createExcludeNodeFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder, @NonNull final NodeId nodeId) {
        final KeyValuePairComponentBuilder keyValuePair = builder.newKeyValuePair("nodeId", nodeId.toString());

        return builder.newFilter("ThreadContextMapFilter", Result.DENY, Result.NEUTRAL)
                .addComponent(keyValuePair);
    }

    /**
     * Creates the set of filters that WHITE-LISTs all {@link #ALLOWED_MARKERS}. Any log event not
     * carrying one of those markers will be DENYed.
     *
     * @param builder the configuration builder
     * @return a composite {@link ComponentBuilder} holding all marker filters
     */
    @NonNull
    private static ComponentBuilder<?> createAllowedMarkerFilters(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        final ComponentBuilder<?> allowedMarkerFilters = builder.newComponent("Filters");
        for (final LogMarker marker : ALLOWED_MARKERS) {
            allowedMarkerFilters.addComponent(builder.newFilter("MarkerFilter", Result.ACCEPT, Result.NEUTRAL)
                    .addAttribute("marker", marker));
        }
        // deny everything else
        allowedMarkerFilters.addComponent(builder.newFilter("DenyAllFilter", Result.DENY, Result.DENY));
        return allowedMarkerFilters;
    }

    /**
     * Creates a filter component that DENYs all events containing any marker listed in
     * {@link #IGNORED_MARKERS}. This is useful for suppressing log output originating from helper
     * threads where {@link ThreadContext} is not properly propagated yet.
     *
     * @param builder the configuration builder
     * @return a composite {@link ComponentBuilder} that suppresses unwanted markers
     */
    @NonNull
    private static ComponentBuilder<?> creatIgnoreMarkerFilters(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        final ComponentBuilder<?> ignoredMarkerFilters = builder.newComponent("Filters");
        for (final LogMarker marker : IGNORED_MARKERS) {
            ignoredMarkerFilters.addComponent(builder.newFilter("MarkerFilter", Result.DENY, Result.NEUTRAL)
                    .addAttribute("marker", marker));
        }

        return ignoredMarkerFilters;
    }

    /**
     * Builds a simple {@code ThresholdFilter} that only allows {@code INFO} and higher level log
     * events to pass through.
     *
     * @param builder the configuration builder
     * @return the created filter component
     */
    @NonNull
    private static FilterComponentBuilder createThresholdFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        return builder.newFilter("ThresholdFilter", Result.NEUTRAL, Result.DENY).addAttribute("level", Level.INFO);
    }

    /**
     * Helper method that constructs a {@code File} appender with sane defaults and attaches the
     * supplied filters.
     *
     * @param builder  the configuration builder
     * @param name     appender name
     * @param layout   the layout to use when writing log lines
     * @param fileName fully-qualified path of the log file
     * @param filters  optional filters; may be {@code null} or empty
     * @return the created file appender component
     */
    @NonNull
    private static AppenderComponentBuilder createFileAppender(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder,
            @NonNull final String name,
            @NonNull final LayoutComponentBuilder layout,
            @NonNull final String fileName,
            @Nullable final ComponentBuilder<?>... filters) {

        return builder.newAppender(name, "File")
                .addAttribute("fileName", fileName)
                .addAttribute("append", true)
                .addComponent(combineFilters(builder, filters))
                .add(layout);
    }

    /**
     * Aggregates all supplied {@code filters} under a single {@code <Filters>} element, as required
     * by Log4j2 XML schema.
     *
     * @param builder the configuration builder
     * @param filters the filters to combine; may be {@code null}
     * @return a composite filter component
     */
    @NonNull
    private static ComponentBuilder<?> combineFilters(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder,
            @Nullable final ComponentBuilder<?>... filters) {
        final ComponentBuilder<?> compositeFilters = builder.newComponent("Filters");
        if (filters != null) {
            for (final ComponentBuilder<?> filter : filters) {
                compositeFilters.addComponent(filter);
            }
        }
        return compositeFilters;
    }
}
