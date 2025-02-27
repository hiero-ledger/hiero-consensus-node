// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.store;

import static com.hedera.node.app.service.token.impl.api.TokenServiceApiProvider.TOKEN_SERVICE_API_PROVIDER;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
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

    private static final Map<Class<?>, ServiceApiProvider<?>> API_PROVIDER =
            Map.of(TokenServiceApi.class, TOKEN_SERVICE_API_PROVIDER);

    public ServiceApiFactory(@NonNull final State state, @NonNull final Configuration configuration) {
        this.state = requireNonNull(state);
        this.configuration = requireNonNull(configuration);
    }

    public <C> C getApi(@NonNull final Class<C> apiInterface) throws IllegalArgumentException {
        requireNonNull(apiInterface);
        final var provider = API_PROVIDER.get(apiInterface);
        if (provider != null) {
            final var writableStates = state.getWritableStates(provider.serviceName());
            final var entityCounters = new WritableEntityIdStore(state.getWritableStates(EntityIdService.NAME));
            final var api = provider.newInstance(configuration, writableStates, entityCounters);
            assert apiInterface.isInstance(api); // This needs to be ensured while apis are registered
            return apiInterface.cast(api);
        }
        throw new IllegalArgumentException("No provider of the given API is available");
    }
}
