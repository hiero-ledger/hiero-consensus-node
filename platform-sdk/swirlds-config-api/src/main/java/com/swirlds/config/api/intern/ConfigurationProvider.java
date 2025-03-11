// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.intern;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.spi.ConfigurationBuilderFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * This class is the only private API of the config API that is used to load an implementation of the config API by
 * using the Java SPI (see {@link ServiceLoader}).
 */
public final class ConfigurationProvider {

    private static final class InstanceHolder {
        private static final ConfigurationProvider INSTANCE = new ConfigurationProvider();
    }

    private final ConfigurationBuilderFactory factory;

    private ConfigurationProvider() {
        factory = loadImplementation(ConfigurationProvider.class.getClassLoader());
    }

    @NonNull
    private static ConfigurationBuilderFactory loadImplementation(@NonNull final ClassLoader classloader) {
        final ServiceLoader<ConfigurationBuilderFactory> serviceLoader =
                ServiceLoader.load(ConfigurationBuilderFactory.class, classloader);
        final Iterator<ConfigurationBuilderFactory> iterator = serviceLoader.iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        throw new IllegalStateException("No ConfigurationBuilderFactory implementation found!");
    }

    /**
     * This method is the facade against an implementation of the config API. The method returns a new {@link
     * ConfigurationBuilder} instance that is created by a concrete implementation.
     *
     * @return a new {@link ConfigurationBuilder} instance
     */
    public ConfigurationBuilder createBuilder() {
        return factory.create();
    }

    /**
     * Access to this class that is defined by a singleton.
     *
     * @return the singleton instance of this class
     */
    public static ConfigurationProvider getInstance() {
        return InstanceHolder.INSTANCE;
    }
}
