// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.log4j.factory;

import com.google.auto.service.AutoService;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.provider.LogProvider;
import com.swirlds.logging.api.extensions.provider.LogProviderFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class is a factory for creating a Log4JProvider to install swirlds-logging to {@link BaseLoggerContext}.
 * <p>
 * Please note that the {@code SwirldsLogAppender} only works if the log4j2 configuration is set to use the
 * {@code SwirldsLogAppender} as the appender for the root logger.
 *
 * @see BaseLoggerContext
 * @see Log4JProvider
 * @see LogProvider
 */
@AutoService(LogProviderFactory.class)
public class Log4JProviderFactory implements LogProviderFactory {
    /**
     * Creates a new instance of the Log4JProvider.
     *
     * @param configuration the configuration to use
     * @return a new instance of the Log4JProvider
     */
    @NonNull
    @Override
    public LogProvider create(@NonNull final Configuration configuration) {
        return new Log4JProvider(configuration);
    }
}
