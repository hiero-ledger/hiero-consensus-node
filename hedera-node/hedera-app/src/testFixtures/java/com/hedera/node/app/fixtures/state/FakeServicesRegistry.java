// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.services.ServicesRegistry;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;

/**
 * A fake implementation of the {@link ServicesRegistry} interface.
 */
public class FakeServicesRegistry implements ServicesRegistry {
    public static final ServicesRegistry.Factory FACTORY =
            (@NonNull final ConstructableRegistry registry, @NonNull final Configuration configuration) ->
                    new FakeServicesRegistry();

    private static final Logger logger = LogManager.getLogger(FakeServicesRegistry.class);
    /**
     * The set of registered services
     */
    private final SortedSet<ServicesRegistry.Registration> entries;

    /**
     * Creates a new registry.
     */
    public FakeServicesRegistry() {
        this.entries = new TreeSet<>();
    }

    /**
     * Register the given service.
     *
     * @param service The service to register
     */
    @Override
    public void register(@NonNull final Service service) {
        final var registry = new FakeSchemaRegistry();
        service.registerSchemas(registry);

        entries.add(new FakeServicesRegistry.Registration(service, registry));
        logger.info("Registered service {}", service.getServiceName());
    }

    @NonNull
    @Override
    public SortedSet<FakeServicesRegistry.Registration> registrations() {
        return Collections.unmodifiableSortedSet(entries);
    }

    @NonNull
    @Override
    public ServicesRegistry subRegistryFor(@NonNull final String... serviceNames) {
        requireNonNull(serviceNames);
        final var selections = Set.of(serviceNames);
        final var subRegistry = new FakeServicesRegistry();
        subRegistry.entries.addAll(entries.stream()
                .filter(registration -> selections.contains(registration.serviceName()))
                .toList());
        return subRegistry;
    }
}
