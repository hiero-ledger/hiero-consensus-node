// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.log4j.factory;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.URI;
import org.apache.logging.log4j.spi.LoggerContext;
import org.apache.logging.log4j.spi.LoggerContextFactory;

/**
 * Implementation of Log4j {@link LoggerContextFactory} SPI.
 * This is a factory to produce the (one and only) {@link BaseLoggerContext} instance.
 * <p>
 * This implementation is inspired by {@code org.apache.logging.log4j.tojul.JULLoggerContextFactory}.
 */
public class BaseLoggerContextFactory implements LoggerContextFactory {
    private static final LoggerContext context = new BaseLoggerContext();

    /**
     * Returns the (one and only) {@link BaseLoggerContext} instance.
     *
     * @param fqcn the fully qualified class name (ignored)
     * @param loader the class loader (ignored)
     * @param externalContext the external context (ignored)
     * @param currentContext whether to return the current context (ignored)
     *
     * @return the (one and only) {@link BaseLoggerContext} instance
     */
    @Override
    @NonNull
    public LoggerContext getContext(
            @Nullable final String fqcn,
            @Nullable final ClassLoader loader,
            @Nullable final Object externalContext,
            final boolean currentContext) {
        return context;
    }

    /**
     * Returns the (one and only) {@link BaseLoggerContext} instance.
     *
     * @param fqcn the fully qualified class name (ignored)
     * @param loader the class loader (ignored)
     * @param externalContext the external context (ignored)
     * @param currentContext whether to return the current context (ignored)
     * @param configLocation The location of the configuration (ignored).
     * @param name The name of the context (ignored).
     *
     * @return the (one and only) {@link BaseLoggerContext} instance
     */
    @Override
    @NonNull
    public LoggerContext getContext(
            @Nullable final String fqcn,
            @Nullable final ClassLoader loader,
            @Nullable final Object externalContext,
            final boolean currentContext,
            @Nullable final URI configLocation,
            @Nullable final String name) {
        return context;
    }

    /**
     * Removes the given context. But since there is only one context, this method does nothing.
     * @param context The context to remove (ignored)
     */
    @Override
    public void removeContext(@Nullable final LoggerContext context) {}

    /**
     * Returns always {@code false} because the context is always the same.
     * @return {@code false} because the context is always the same
     */
    @Override
    public boolean isClassLoaderDependent() {
        return false;
    }
}
