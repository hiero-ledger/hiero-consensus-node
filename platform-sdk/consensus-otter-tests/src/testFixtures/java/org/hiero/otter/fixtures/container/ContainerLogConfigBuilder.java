// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.DEFAULT_PATTERN;
import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.createThresholdFilter;

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
 * Builds and installs a Log4j2 configuration used by the host system in a container environment.
 * <p>
 * The configuration is created programmatically (no XML) and ensures that the host system
 * has a simple console appender that outputs log events at the {@code INFO} level and higher.
 */
public final class ContainerLogConfigBuilder {

    private ContainerLogConfigBuilder() {
        // utility
    }

    /**
     * Configures the logging system with a simple console appender that outputs log events at the {@code INFO} level and higher.
     */
    public static void configure() {
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

        LogManager.getLogger(ContainerLogConfigBuilder.class).info("Test logging configuration (re)initialized");
    }
}
