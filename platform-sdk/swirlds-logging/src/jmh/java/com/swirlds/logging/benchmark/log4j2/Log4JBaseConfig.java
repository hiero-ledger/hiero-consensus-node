// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.log4j2;

import com.swirlds.logging.benchmark.config.LoggingBenchmarkConfig;
import com.swirlds.logging.benchmark.util.ConfigManagement;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.spi.LoggerContext;

/**
 * Convenience methods for configuring log4j logger
 */
public abstract class Log4JBaseConfig implements LoggingBenchmarkConfig<LoggerContext> {

    protected static final String PATTERN =
            (ConfigManagement.formatTimestamp() ? "%d{yyyy-MM-dd HH:mm:ss.SSS}" : "%d{UNIX_MILLIS}")
                    + " %-5level [%t] %c - %msg - [%marker] %X %n%throwable";
    private static final String CONSOLE_APPENDER_NAME = "console";
    protected static final String FILE_APPENDER_NAME = "file";

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public LoggerContext configureConsoleLogging() {
        System.clearProperty("log4j2.contextSelector");
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.ERROR);
        builder.setConfigurationName("consoleLoggingConfig");
        builder.add(createConsoleAppender(builder));
        builder.add(builder.newRootLogger(Level.DEBUG).add(builder.newAppenderRef(CONSOLE_APPENDER_NAME)));
        return create(builder);
    }

    /**
     * {@inheritDoc}
     * @param logFile
     */
    @NonNull
    @Override
    public LoggerContext configureFileLogging(final String logFile) {
        System.clearProperty("log4j2.contextSelector");
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.DEBUG);
        builder.setConfigurationName("fileLoggingConfig");
        builder.add(createFileAppender(builder, logFile));
        builder.add(builder.newRootLogger(Level.DEBUG).add(builder.newAppenderRef(FILE_APPENDER_NAME)));
        return create(builder);
    }

    /**
     * {@inheritDoc}
     * @param logFile
     */
    @NonNull
    @Override
    public LoggerContext configureFileAndConsoleLogging(final String logFile) {
        System.clearProperty("log4j2.contextSelector");
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.ERROR);
        builder.setConfigurationName("fileAndConsoleLoggingConfig");
        builder.add(createFileAppender(builder, logFile));
        builder.add(createConsoleAppender(builder));
        builder.add(builder.newRootLogger(Level.DEBUG)
                .add(builder.newAppenderRef(FILE_APPENDER_NAME))
                .add(builder.newAppenderRef(CONSOLE_APPENDER_NAME)));
        return create(builder);
    }

    @NonNull
    protected static LoggerContext create(final @NonNull ConfigurationBuilder<BuiltConfiguration> builder) {
        final org.apache.logging.log4j.core.config.Configuration configuration = builder.build();
        final org.apache.logging.log4j.core.LoggerContext context = Configurator.initialize(configuration);
        LogManager.getFactory().removeContext(context);
        return Configurator.initialize(configuration);
    }

    @NonNull
    private static AppenderComponentBuilder createConsoleAppender(
            final @NonNull ConfigurationBuilder<BuiltConfiguration> builder) {
        final LayoutComponentBuilder layoutComponentBuilder =
                builder.newLayout("PatternLayout").addAttribute("pattern", PATTERN);
        return builder.newAppender(Log4JBaseConfig.CONSOLE_APPENDER_NAME, "CONSOLE")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT)
                .add(layoutComponentBuilder);
    }

    @NonNull
    protected abstract AppenderComponentBuilder createFileAppender(
            final @NonNull ConfigurationBuilder<BuiltConfiguration> builder, final @NonNull String path);
}
