// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.logging;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.DEFAULT_PATTERN;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.combineFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.configureHashStreamFilter;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createAllowedMarkerFilters;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createFileAppender;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createThresholdFilter;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
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
 * Builds and installs a Log4j2 configuration used by consensus nodes running in a container.
 * <p>
 * The configuration is created programmatically (no XML) and follows this guide:
 * <ul>
 *     <li>Two log files ({@code swirlds.log} and {@code swirlds-hashstream.log})</li>
 *     <li>Console output that mirrors {@code swirlds.log}</li>
 *     <li>In-memory appender for tests</li>
 * </ul>
 */
public final class DockerLogConfigBuilder {

    private DockerLogConfigBuilder() {
        // utility
    }

    /**
     * Installs a new Log4j2 configuration that logs into the given {@code logDir}.
     * The configuration is <em>global</em> (i.e. affects the entire JVM).
     *
     * @param baseDir     directory where log files are written (created automatically)
     * @param nodeId      the node ID for which this configuration is created, or {@code null} if not applicable
     * @throws NullPointerException if {@code baseDir} is {@code null}
     */
    public static void configure(@NonNull final Path baseDir, @Nullable final NodeId nodeId) {
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

        // In-memory appender for tests
        builder.add(builder.newAppender("InMemory", "DockerInMemoryAppender")
                .addAttribute("nodeId", nodeId == null ? -1L : nodeId.id()));

        final RootLoggerComponentBuilder root = builder.newRootLogger(Level.ALL)
                .add(builder.newAppenderRef("InMemory"))
                .add(builder.newAppenderRef("FileLogger"))
                .add(builder.newAppenderRef("HashStreamLogger"))
                .add(builder.newAppenderRef("ConsoleLogger"));
        // Register the root logger with the configuration
        builder.add(root);

        Configurator.reconfigure(builder.build());

        LogManager.getLogger(DockerLogConfigBuilder.class).info("Logging configuration (re)initialized");
    }
}
