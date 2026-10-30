// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.hiero.otter.fixtures.logging.internal.LogConfigHelper.DEFAULT_PATTERN;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender.Target;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * Builds and installs the Log4j2 configuration of the Falcon environment.
 *
 * <p>A Falcon sweep runs a test hundreds of times, and a failing repetition can be reproduced from its seed. Falcon
 * therefore only writes warnings and errors to the console, which also keeps the captured test output small.
 */
final class FalconLogConfigBuilder {

    private static final String CONFIGURATION_NAME = "FalconLogging";

    private FalconLogConfigBuilder() {
        // utility
    }

    /**
     * Installs the Falcon logging configuration, unless it is already installed. Other environments that ran earlier
     * in the same JVM may have installed their own configuration, which is replaced.
     */
    static void configure() {
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        if (CONFIGURATION_NAME.equals(context.getConfiguration().getName())) {
            return;
        }

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setConfigurationName(CONFIGURATION_NAME);
        builder.setStatusLevel(Level.ERROR);

        builder.add(builder.newAppender("Console", "Console")
                .addAttribute("target", Target.SYSTEM_OUT)
                .add(builder.newLayout("PatternLayout").addAttribute("pattern", DEFAULT_PATTERN)));
        builder.add(builder.newRootLogger(Level.WARN).add(builder.newAppenderRef("Console")));

        Configurator.reconfigure(builder.build());
    }
}
