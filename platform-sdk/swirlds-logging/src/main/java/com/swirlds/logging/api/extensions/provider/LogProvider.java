// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.provider;

import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogEventConsumer;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A log provider that can be used to provide log events from custom logging implementations. An example would be an
 * implementation that converts Log4J events to our {@link LogEvent} format and than forwards them to our logging.
 *
 * <p>
 * Log providers are created by {@link LogProviderFactory} instances. The factory uses SPI.
 *
 * @see LogProviderFactory
 */
public interface LogProvider {

    /**
     * Checks if the log provider is active. If the log provider is not active, it will not be used. This can be used to
     * disable a log provider without removing it from the configuration. The current logging implementation checks that
     * state at startup and not for every log event.
     *
     * @return true if the log provider is active, false otherwise
     */
    boolean isActive();

    /**
     * Returns the name of the log provider.
     *
     * @return the name of the log provider
     */
    @NonNull
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * Installs the log event consumer. The log provider should forward all log events to the consumer.
     *
     * @param logEventConsumer the log event consumer
     */
    void install(@NonNull LogEventFactory logEventFactory, @NonNull LogEventConsumer logEventConsumer);
}
