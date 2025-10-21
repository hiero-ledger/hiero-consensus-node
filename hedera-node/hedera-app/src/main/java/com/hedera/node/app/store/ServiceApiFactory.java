// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.store;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * A factory for creating service APIs based on a {@link State}.
 */
public class ServiceApiFactory {
    private final State state;
    private final Configuration configuration;
    private final Map<Class<?>, ServiceApiProvider<?>> apiProviders;

    public ServiceApiFactory(
            @NonNull final State state,
            @NonNull final Configuration configuration,
            @NonNull final Map<Class<?>, ServiceApiProvider<?>> apiProviders) {
        this.state = requireNonNull(state);
        this.configuration = requireNonNull(configuration);
        this.apiProviders = requireNonNull(apiProviders);
    }

    public <C> C getApi(@NonNull final Class<C> apiInterface) throws IllegalArgumentException {
        requireNonNull(apiInterface);
        final var provider = apiProviders.get(apiInterface);
        if (provider != null) {
            final var writableStates = state.getWritableStates(provider.serviceName());
            final var entityCounters = new WritableEntityIdStoreImpl(state.getWritableStates(EntityIdService.NAME));
            final var api = provider.newInstance(configuration, writableStates, entityCounters);
            assert apiInterface.isInstance(api); // This needs to be ensured while apis are registered
            return apiInterface.cast(api);
        }
        throw new IllegalArgumentException("No provider of the given API is available");
    }
}
