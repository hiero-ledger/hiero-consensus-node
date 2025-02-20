// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.handler;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A factory that creates {@link LogHandler}s. The factory is used by the Java SPI to create log handlers.
 *
 * @see LogHandler
 * @see java.util.ServiceLoader
 */
public interface LogHandlerFactory {

    /**
     * Creates a new log handler.
     *
     * @param handlerName the configuration key for the log handler
     * @param configuration the configuration
     * @return the log handler
     */
    @NonNull
    LogHandler create(@NonNull String handlerName, @NonNull Configuration configuration);

    /**
     * Name used to reference a handler type in the configuration. If the name is "console", then the configuration
     * would look like:
     * <pre>
     *     logging.handler.NAME.type=console
     * </pre>
     *
     * @return name
     */
    @NonNull
    String getTypeName();
}
