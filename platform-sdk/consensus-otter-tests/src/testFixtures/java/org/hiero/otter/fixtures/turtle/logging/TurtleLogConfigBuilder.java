// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.logging;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.DEFAULT_PATTERN;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.combineFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.configureHashStreamFilter;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createAllowedMarkerFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createExcludeNodeFilter;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createFileAppender;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createIgnoreMarkerFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createThresholdFilter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.hiero.consensus.model.node.NodeId;

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
        builder.setConfigurationName("TurtleLogging");
        builder.setStatusLevel(Level.ERROR);

        final LayoutComponentBuilder standardLayout =
                builder.newLayout("PatternLayout").addAttribute("pattern", DEFAULT_PATTERN);

        final ComponentBuilder<?> perNodeRoutes =
                builder.newComponent("Routes").addAttribute("pattern", "$${ctx:nodeId:-unknown}");
        final ComponentBuilder<?> perNodeHashRoutes =
                builder.newComponent("Routes").addAttribute("pattern", "$${ctx:nodeId:-unknown}");
        final List<FilterComponentBuilder> excludeNodeFilters = new ArrayList<>();

        for (final Map.Entry<NodeId, Path> entry : nodeLogDirs.entrySet()) {
            final String nodeId = Long.toString(entry.getKey().id());

            excludeNodeFilters.add(createExcludeNodeFilter(builder, entry.getKey()));

            final AppenderComponentBuilder fileAppender = createFileAppender(
                    builder,
                    "FileLogger-" + nodeId,
                    standardLayout,
                    entry.getValue().resolve("output/swirlds.log").toString(),
                    createThresholdFilter(builder),
                    createAllowedMarkerFilters(builder));
            builder.add(fileAppender);
            perNodeRoutes.addComponent(builder.newComponent("Route")
                    .addAttribute("key", nodeId)
                    .addAttribute("ref", fileAppender.getName()));

            final AppenderComponentBuilder hashAppender = createFileAppender(
                    builder,
                    "HashStreamLogger-" + nodeId,
                    standardLayout,
                    entry.getValue()
                            .resolve("output/swirlds-hashstream/swirlds-hashstream.log")
                            .toString(),
                    configureHashStreamFilter(builder));
            builder.add(hashAppender);
            perNodeHashRoutes.addComponent(builder.newComponent("Route")
                    .addAttribute("key", nodeId)
                    .addAttribute("ref", hashAppender.getName()));
        }

        final AppenderComponentBuilder fallbackFileAppender = createFileAppender(
                builder,
                "FileLogger-unknown",
                standardLayout,
                ensureFilePath(baseDir.resolve("node-unknown/output/swirlds.log")),
                createThresholdFilter(builder),
                createAllowedMarkerFilters(builder));
        builder.add(fallbackFileAppender);
        perNodeRoutes.addComponent(builder.newComponent("Route")
                .addAttribute("key", "unknown")
                .addAttribute("ref", fallbackFileAppender.getName()));

        final AppenderComponentBuilder fallbackHashAppender = createFileAppender(
                builder,
                "HashStreamLogger-unknown",
                standardLayout,
                ensureFilePath(baseDir.resolve("node-unknown/output/swirlds-hashstream/swirlds-hashstream.log")),
                configureHashStreamFilter(builder));
        builder.add(fallbackHashAppender);
        perNodeHashRoutes.addComponent(builder.newComponent("Route")
                .addAttribute("key", "unknown")
                .addAttribute("ref", fallbackHashAppender.getName()));

        final AppenderComponentBuilder routingAppender =
                builder.newAppender("PerNodeRouting", "Routing").addComponent(perNodeRoutes);
        builder.add(routingAppender);

        final AppenderComponentBuilder routingHashAppender =
                builder.newAppender("PerNodeHashRouting", "Routing").addComponent(perNodeHashRoutes);
        builder.add(routingHashAppender);

        final ComponentBuilder<?> excludeNodeFilter =
                combineFilters(builder, excludeNodeFilters.toArray(new FilterComponentBuilder[0]));

        final ComponentBuilder<?> consoleFilters = combineFilters(
                builder, createThresholdFilter(builder), excludeNodeFilter, createIgnoreMarkerFilters(builder));

        final AppenderComponentBuilder consoleAppender = builder.newAppender("Console", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(standardLayout)
                .addComponent(consoleFilters);
        builder.add(consoleAppender);

        builder.add(builder.newAppender(IN_MEMORY_APPENDER_NAME, "TurtleInMemoryAppender"));

        final RootLoggerComponentBuilder root = builder.newRootLogger(Level.ALL)
                .add(builder.newAppenderRef(IN_MEMORY_APPENDER_NAME))
                .add(builder.newAppenderRef("Console"))
                .add(builder.newAppenderRef("PerNodeRouting"))
                .add(builder.newAppenderRef("PerNodeHashRouting"));

        builder.add(root);

        builder.add(builder.newLogger("org.hiero.otter", Level.INFO)
                .add(builder.newAppenderRef("Console"))
                .addAttribute("additivity", false));

        Configurator.reconfigure(builder.build());

        LogManager.getLogger(TurtleLogConfigBuilder.class).info("Unified logging configuration (re)initialized");
    }

    @NonNull
    private static String ensureFilePath(@NonNull final Path path) {
        requireNonNull(path, "path must not be null");
        final Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (final IOException e) {
                throw new IllegalStateException("Unable to create log directory " + parent, e);
            }
        }
        return path.toString();
    }
}
