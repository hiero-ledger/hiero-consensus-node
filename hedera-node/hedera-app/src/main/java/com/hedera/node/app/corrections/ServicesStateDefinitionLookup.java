// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.corrections;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.state.merkle.MerkleSchemaRegistry;
import com.hedera.node.config.ConfigProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A {@link StateDefinitionLookup} derived from the schemas registered by every service in the
 * {@link ServicesRegistry}. Because it does not depend on any particular {@code State} implementation, it gives
 * the same answers for a node's real state and for in-memory test states.
 */
@Singleton
public class ServicesStateDefinitionLookup implements StateDefinitionLookup {
    private final ServicesRegistry servicesRegistry;
    private final ConfigProvider configProvider;

    @Nullable
    private volatile Map<Integer, ServiceStateDefinition> definitions;

    @Inject
    public ServicesStateDefinitionLookup(
            @NonNull final ServicesRegistry servicesRegistry, @NonNull final ConfigProvider configProvider) {
        this.servicesRegistry = requireNonNull(servicesRegistry);
        this.configProvider = requireNonNull(configProvider);
    }

    @Override
    public @Nullable ServiceStateDefinition lookup(final int stateId) {
        var current = definitions;
        if (current == null) {
            current = loadDefinitions();
            definitions = current;
        }
        return current.get(stateId);
    }

    private Map<Integer, ServiceStateDefinition> loadDefinitions() {
        final var configuration = configProvider.getConfiguration();
        final Map<Integer, ServiceStateDefinition> loaded = new HashMap<>();
        for (final var registration : servicesRegistry.registrations()) {
            if (registration.registry() instanceof MerkleSchemaRegistry schemaRegistry) {
                schemaRegistry
                        .currentStateDefinitions(configuration)
                        .forEach((stateId, definition) -> loaded.put(
                                stateId, new ServiceStateDefinition(registration.serviceName(), definition)));
            }
        }
        return Map.copyOf(loaded);
    }
}
