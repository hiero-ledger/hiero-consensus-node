// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
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
 * Provides logging configurations and functionality for the Turtle framework.
 */
public class TurtleLogging {

    private final Path globalLogFile;
    private final Map<NodeId, Path> nodeIdConfigurations = new ConcurrentHashMap<>();

    public TurtleLogging(@NonNull final Path rootOutputDirectory) {
        globalLogFile = rootOutputDirectory.resolve("otter.log");
        updateLogging();
    }

    public void addNodeLogging(@NonNull final NodeId nodeId, @NonNull final Path outputDirectory) {
        requireNonNull(nodeId, "nodeId cannot be null");
        requireNonNull(outputDirectory, "outputDirectory cannot be null");
        nodeIdConfigurations.put(nodeId, outputDirectory);
        updateLogging();
    }

    public void removeNodeLogging(@NonNull final NodeId nodeId) {
        nodeIdConfigurations.remove(nodeId);
        updateLogging();
    }

    private void updateLogging() {
        final ConfigurationBuilder<BuiltConfiguration> configuration =
                ConfigurationBuilderFactory.newConfigurationBuilder();

        final LayoutComponentBuilder globalLogLayout = configuration
                .newLayout("PatternLayout")
                .addAttribute(
                        "pattern",
                        "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %notEmpty{[%marker] }%-5level %logger{36} - %msg %n");
        final LayoutComponentBuilder nodeLogLayout = configuration
                .newLayout("PatternLayout")
                .addAttribute(
                        "pattern",
                        "%d{yyyy-MM-dd HH:mm:ss.SSS} [nodeId-%X{nodeId}] [%t] %notEmpty{[%marker] }%-5level %logger{36} - %msg %n");

        final FilterComponentBuilder infoFilter = configuration
                .newFilter("ThresholdFilter", Result.NEUTRAL, Result.DENY)
                .addAttribute("level", Level.INFO);

        final ComponentBuilder<?> globalFilter =
                configuration.newComponent("filters").addComponent(infoFilter);

        final RootLoggerComponentBuilder rootLogger = configuration.newRootLogger(Level.ALL);

        for (final Map.Entry<NodeId, Path> entry : nodeIdConfigurations.entrySet()) {
            final NodeId nodeId = entry.getKey();
            final Path outputDirectory = entry.getValue();
            final KeyValuePairComponentBuilder keyValuePair =
                    configuration.newKeyValuePair("nodeId", String.valueOf(nodeId.id()));

            final FilterComponentBuilder excludeNodeFilter = configuration
                    .newFilter("ThreadContextMapFilter", Result.DENY, Result.NEUTRAL)
                    .addComponent(keyValuePair);
            globalFilter.addComponent(excludeNodeFilter);

            final FilterComponentBuilder nodeOnlyFilter = configuration
                    .newFilter("ThreadContextMapFilter", Result.NEUTRAL, Result.DENY)
                    .addComponent(keyValuePair);
            final FilterComponentBuilder excludeHashStateFilter = configuration
                    .newFilter("MarkerFilter", Result.DENY, Result.NEUTRAL)
                    .addAttribute("marker", "STATE_HASH");
            final FilterComponentBuilder hashStateOnlyFilter = configuration
                    .newFilter("MarkerFilter", Result.NEUTRAL, Result.DENY)
                    .addAttribute("marker", "STATE_HASH");

            final ComponentBuilder<?> regularNodeFilter = configuration
                    .newComponent("filters")
                    .addComponent(infoFilter)
                    .addComponent(nodeOnlyFilter)
                    .addComponent(excludeHashStateFilter);
            final AppenderComponentBuilder regularNodeFileAppender = configuration
                    .newAppender("FileLogger-" + nodeId, "File")
                    .addAttribute(
                            "fileName", outputDirectory.resolve("swirlds.log").toString())
                    .addAttribute("append", true)
                    .add(nodeLogLayout)
                    .addComponent(regularNodeFilter);

            final ComponentBuilder<?> hashStateNodeFilter = configuration
                    .newComponent("filters")
                    .addComponent(infoFilter)
                    .addComponent(nodeOnlyFilter)
                    .addComponent(hashStateOnlyFilter);
            final AppenderComponentBuilder hashStateFileAppender = configuration
                    .newAppender("HashStreamLogger-" + nodeId, "File")
                    .addAttribute(
                            "fileName",
                            outputDirectory.resolve("swirlds-hashstream.log").toString())
                    .addAttribute("append", true)
                    .add(nodeLogLayout)
                    .addComponent(hashStateNodeFilter);

            configuration.add(regularNodeFileAppender);
            configuration.add(hashStateFileAppender);
            rootLogger.add(configuration.newAppenderRef("FileLogger-" + nodeId));
            rootLogger.add(configuration.newAppenderRef("HashStreamLogger-" + nodeId));
        }

        final AppenderComponentBuilder inMemoryAppender = configuration.newAppender("InMemory", "InMemoryAppender");
        final AppenderComponentBuilder consoleAppender = configuration
                .newAppender("ConsoleMarker", "Console")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT)
                .add(globalLogLayout)
                .addComponent(globalFilter);
        final AppenderComponentBuilder globalFileAppender = configuration
                .newAppender("FileLogger", "File")
                .addAttribute("fileName", globalLogFile.toString())
                .addAttribute("append", true)
                .add(globalLogLayout)
                .addComponent(globalFilter);

        configuration.add(inMemoryAppender).add(consoleAppender).add(globalFileAppender);

        rootLogger
                .add(configuration.newAppenderRef("InMemory"))
                .add(configuration.newAppenderRef("ConsoleMarker"))
                .add(configuration.newAppenderRef("FileLogger"));

        configuration.add(rootLogger);

        Configurator.reconfigure(configuration.build());
    }
}
