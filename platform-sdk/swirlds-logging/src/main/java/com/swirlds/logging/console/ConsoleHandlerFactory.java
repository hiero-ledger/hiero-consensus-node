// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.console;

import com.google.auto.service.AutoService;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.handler.LogHandler;
import com.swirlds.logging.api.extensions.handler.LogHandlerFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A factory for creating {@link ConsoleHandler} instances.
 * <p>
 * This class implements the {@link LogHandlerFactory} interface and is responsible for creating instances of the
 * {@link ConsoleHandler} class with the provided {@link Configuration}.
 *
 * @see LogHandlerFactory
 * @see ConsoleHandler
 * @see Configuration
 */
@AutoService(LogHandlerFactory.class)
public class ConsoleHandlerFactory implements LogHandlerFactory {

    public static final String CONSOLE_HANDLER_TYPE = "console";

    /**
     * Creates a new {@link ConsoleHandler} instance with the specified {@link Configuration}.
     *
     * @param handlerName   The name of the handler instance.
     * @param configuration The configuration for the new handler instance.
     * @return A new {@link ConsoleHandler} instance.
     * @throws NullPointerException if the provided {@code configuration} is {@code null}.
     */
    @Override
    @NonNull
    public LogHandler create(@NonNull final String handlerName, @NonNull final Configuration configuration) {
        return new ConsoleHandler(handlerName, configuration, true);
    }

    @NonNull
    @Override
    public String getTypeName() {
        return CONSOLE_HANDLER_TYPE;
    }
}
