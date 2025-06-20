// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.log4j.factory;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.event.LogEventConsumer;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import com.swirlds.logging.api.extensions.provider.AbstractLogProvider;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Install the {@link LogEventFactory} and {@link LogEventConsumer} to the {@link BaseLoggerContext}.
 */
public class Log4JProvider extends AbstractLogProvider {

    /**
     * Name for the config key for the log provider.
     * The handler will be called with {@code logging.provider.log4j} prefix.
     */
    private static final String CONFIG_KEY = "log4j";

    /**
     * Creates a new log provider.
     *
     * @param configuration the configuration
     */
    public Log4JProvider(@NonNull final Configuration configuration) {
        super(CONFIG_KEY, configuration);
    }

    /**
     * Installs the {@link LogEventFactory} and {@link LogEventConsumer} to the {@link BaseLoggerContext}.
     *
     * @param logEventFactory the log event factory
     * @param logEventConsumer the log event consumer
     */
    @Override
    public void install(
            @NonNull final LogEventFactory logEventFactory, @NonNull final LogEventConsumer logEventConsumer) {
        BaseLoggerContext.initBaseLogging(logEventFactory, logEventConsumer);
    }
}
