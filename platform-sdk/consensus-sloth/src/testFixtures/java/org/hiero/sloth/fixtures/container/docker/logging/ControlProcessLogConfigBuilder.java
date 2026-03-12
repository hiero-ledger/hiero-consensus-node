// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker.logging;

import static java.util.Objects.requireNonNull;
import static org.hiero.sloth.fixtures.logging.internal.LogConfigHelper.DEFAULT_PATTERN;
import static org.hiero.sloth.fixtures.logging.internal.LogConfigHelper.createFileAppender;
import static org.hiero.sloth.fixtures.logging.internal.LogConfigHelper.createThresholdFilter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.appender.ConsoleAppender.Target;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.FilterComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * Builds and installs a Log4j2 configuration used by the control process in a container.
 */
public final class ControlProcessLogConfigBuilder {

    private ControlProcessLogConfigBuilder() {
        // utility
    }

    /**
     * Installs a new Log4j2 configuration that logs control process output to sloth.log.
     *
     * @param baseDir directory where log files are written (created automatically)
     */
    public static void configure(@NonNull final Path baseDir) {
        requireNonNull(baseDir, "baseDir must not be null");
        final Path defaultLogDir = baseDir.resolve("output");

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        final LayoutComponentBuilder standardLayout =
                builder.newLayout("PatternLayout").addAttribute("pattern", DEFAULT_PATTERN);

        final FilterComponentBuilder thresholdInfoFilter = createThresholdFilter(builder);

        final AppenderComponentBuilder otterLogAppender = createFileAppender(
                builder,
                "SlothLogger",
                standardLayout,
                defaultLogDir.resolve("sloth.log").toString(),
                thresholdInfoFilter);
        builder.add(otterLogAppender);

        final AppenderComponentBuilder consoleAppender = builder.newAppender("ConsoleLogger", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(standardLayout)
                .addComponent(thresholdInfoFilter);
        builder.add(consoleAppender);

        final RootLoggerComponentBuilder root = builder.newRootLogger(Level.ALL)
                .add(builder.newAppenderRef("SlothLogger"))
                .add(builder.newAppenderRef("ConsoleLogger"));

        builder.add(root);

        Configurator.reconfigure(builder.build());

        LogManager.getLogger(ControlProcessLogConfigBuilder.class)
                .info("Control process logging configuration initialized");
    }
}
