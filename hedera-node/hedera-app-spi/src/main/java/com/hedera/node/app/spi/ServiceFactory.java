// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi;

import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * This class provides the ability to load {@link Service} implementations at runtime. The Java SPI
 * (see {@link ServiceLoader}) is used to provide such information at runtime. Since we use the Java
 * module system the {@link ServiceLoader} instance can not be created in the factory. It must be
 * created in the module that add "uses" information for the module to the {@code module-info.java}.
 */
public final class ServiceFactory {

    private ServiceFactory() {}

    /**
     * This method returns a service instance of the given service that is provided by the Java SPI.
     *
     * @param type the service type
     * @param serviceLoader the service loaded that will be used
     * @param <S> the service type
     * @return the service instance
     * @throws IllegalStateException if no or multiple services are found
     */
    @NonNull
    public static <S extends Service> S loadService(
            @NonNull final Class<S> type, @NonNull final ServiceLoader<S> serviceLoader) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(serviceLoader, "serviceLoader must not be null");
        final Iterator<S> iterator = serviceLoader.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException("No service implementation found for service type '" + type + "'");
        }
        final S serviceInstance = iterator.next();
        if (iterator.hasNext()) {
            throw new IllegalStateException("Multiple service implementations found for service type '" + type + "'");
        }
        return serviceInstance;
    }
}
