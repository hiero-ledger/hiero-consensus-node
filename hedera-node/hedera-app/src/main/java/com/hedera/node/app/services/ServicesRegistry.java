// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.Set;
import org.hiero.base.constructable.ConstructableRegistry;

/**
 * A registry providing access to all services registered with the application.
 */
public interface ServicesRegistry {
    /**
     * A factory for creating a {@link ServicesRegistry}.
     */
    interface Factory {
        ServicesRegistry create(
                @NonNull ConstructableRegistry constructableRegistry, @NonNull Configuration bootstrapConfig);
    }

    /**
     * A record of a service registration.
     *
     * @param service The service that was registered
     * @param registry The schema registry for the service
     */
    record Registration(@NonNull Service service, @NonNull SchemaRegistry registry)
            implements Comparable<Registration> {
        private static final Comparator<Registration> COMPARATOR = Comparator.<Registration>comparingInt(
                        r -> r.service().migrationOrder())
                .thenComparing(r -> r.service().getServiceName());

        public Registration {
            requireNonNull(service);
            requireNonNull(registry);
        }

        public String serviceName() {
            return service.getServiceName();
        }

        @Override
        public int compareTo(@NonNull final Registration that) {
            return COMPARATOR.compare(this, that);
        }
    }

    /**
     * Gets the full set of services registered, sorted deterministically.
     *
     * @return The set of services. May be empty.
     */
    @NonNull
    Set<Registration> registrations();

    /**
     * Register a service with the registry.
     * @param service The service to register
     */
    void register(@NonNull Service service);

    /**
     * Returns a sub-registry containing only the services with the given names.
     * @param serviceNames the names of the services to include in the sub-registry
     * @return the sub-registry
     */
    @NonNull
    ServicesRegistry subRegistryFor(@NonNull String... serviceNames);
}
