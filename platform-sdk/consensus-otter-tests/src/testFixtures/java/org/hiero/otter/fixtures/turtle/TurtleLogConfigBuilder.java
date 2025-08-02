// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.DEFAULT_PATTERN;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.combineFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.configureHashStreamFilter;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.creatIgnoreMarkerFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createAllowedMarkerFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createExcludeNodeFilter;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createFileAppender;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createNodeOnlyFilter;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createThresholdFilter;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
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
 * Builds and installs a Log4j2 configuration running in a Turtle environment.
 * <p>
 * The configuration is created programmatically (no XML) and follows this guide:
 * <ul>
 *     <li>Two log files per node ({@code swirlds.log} and {@code swirlds-hashstream.log})</li>
 *     <li>Console output that mirrors {@code swirlds.log}</li>
 *     <li>Per-node routing via {@link ThreadContext}</li>
 *     <li>In-memory appender for tests</li>
 * </ul>
 */
public final class TurtleLogConfigBuilder {

    public static final String IN_MEMORY_APPENDER_NAME = "InMemory";
    public static final String IN_MEMORY_MANAGER_NAME = IN_MEMORY_APPENDER_NAME + ".manager";

    private TurtleLogConfigBuilder() {
        // utility
    }

    /**
     * Installs a new Log4j2 configuration that logs into the given directories. The map argument
     * allows callers to specify per-node output directories.
     * For all nodes contained in the map an individual set of appenders is created.
     *
     * @param baseDir       directory used when no per-node mapping is provided
     * @param nodeLogDirs   mapping (node-ID  ➔  directory) for per-node log routing
     */
    public static void configure(@NonNull final Path baseDir, @NonNull final Map<NodeId, Path> nodeLogDirs) {
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
        builder.add(builder.newAppender("InMemory", "TurtleInMemoryAppender"));

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

        LogManager.getLogger(TurtleLogConfigBuilder.class).info("Unified logging configuration (re)initialized");
    }
}
