// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.swirldslog.plain;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.logging.api.internal.LoggingSystem;
import com.swirlds.logging.api.internal.configuration.ConfigLevelConverter;
import com.swirlds.logging.api.internal.configuration.MarkerStateConverter;
import com.swirlds.logging.benchmark.config.LoggingBenchmarkConfig;
import com.swirlds.logging.benchmark.util.ConfigManagement;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Convenience methods for configuring swirlds-logging logger
 */
public class SwirldsLogConfig implements LoggingBenchmarkConfig<LoggingSystem> {

    /**
     * {@inheritDoc}
     * @param logFile
     */
    public @NonNull LoggingSystem configureFileLogging(final String logFile) {
        final com.swirlds.config.api.Configuration configuration = ConfigurationBuilder.create()
                .withConverter(new ConfigLevelConverter())
                .withConverter(new MarkerStateConverter())
                .withValue("logging.level", "trace")
                .withValue("logging.handler.file.type", "file")
                .withValue("logging.handler.file.enabled", "true")
                .withValue("logging.handler.file.formatTimestamp", ConfigManagement.formatTimestamp() + "")
                .withValue("logging.handler.file.level", "trace")
                .withValue("logging.handler.file.file", logFile)
                .build();

        return configure(configuration);
    }

    /**
     * {@inheritDoc}
     */
    public @NonNull LoggingSystem configureConsoleLogging() {
        final com.swirlds.config.api.Configuration configuration = ConfigurationBuilder.create()
                .withConverter(new ConfigLevelConverter())
                .withConverter(new MarkerStateConverter())
                .withValue("logging.level", "trace")
                .withValue("logging.handler.console.type", "console")
                .withValue("logging.handler.console.enabled", "true")
                .withValue("logging.handler.console.formatTimestamp", ConfigManagement.formatTimestamp() + "")
                .withValue("logging.handler.console.level", "trace")
                .build();

        return configure(configuration);
    }

    /**
     * {@inheritDoc}
     * @param logFile
     */
    public @NonNull LoggingSystem configureFileAndConsoleLogging(final String logFile) {
        final com.swirlds.config.api.Configuration configuration = ConfigurationBuilder.create()
                .withConverter(new ConfigLevelConverter())
                .withConverter(new MarkerStateConverter())
                .withValue("logging.level", "trace")
                .withValue("logging.handler.file.type", "file")
                .withValue("logging.handler.file.enabled", "true")
                .withValue("logging.handler.file.formatTimestamp", ConfigManagement.formatTimestamp() + "")
                .withValue("logging.handler.file.level", "trace")
                .withValue("logging.handler.file.file", logFile)
                .withValue("logging.handler.console.type", "console")
                .withValue("logging.handler.console.enabled", "true")
                .withValue("logging.handler.console.formatTimestamp", ConfigManagement.formatTimestamp() + "")
                .withValue("logging.handler.console.level", "trace")
                .build();

        return configure(configuration);
    }

    @NonNull
    private LoggingSystem configure(@NonNull final Configuration configuration) {
        LoggingSystem loggingSystem = new LoggingSystem(configuration);
        loggingSystem.installHandlers();
        loggingSystem.installProviders();
        return loggingSystem;
    }
}
