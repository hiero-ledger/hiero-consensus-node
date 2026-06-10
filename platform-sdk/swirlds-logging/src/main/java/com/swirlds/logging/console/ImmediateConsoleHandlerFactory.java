// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.console;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.handler.LogHandler;
import com.swirlds.logging.api.extensions.handler.LogHandlerFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * SPI factory for {@link ImmediateConsoleHandler}. Registered via {@code provides} in
 * {@code module-info.java}; selected from {@code log.properties} by setting
 * {@code logging.handler.<name>.type=console-immediate}.
 *
 * <p>Kept as a top-level class because the Hiero Gradle plugin's auto-generated
 * {@code META-INF/services} file uses dot-notation for nested classes, which the JDK
 * {@link java.util.ServiceLoader} rejects.
 */
public final class ImmediateConsoleHandlerFactory implements LogHandlerFactory {

    @NonNull
    @Override
    public LogHandler create(@NonNull final String handlerName, @NonNull final Configuration configuration) {
        return new ImmediateConsoleHandler(handlerName, configuration);
    }

    @NonNull
    @Override
    public String getTypeName() {
        return ImmediateConsoleHandler.TYPE_NAME;
    }
}
