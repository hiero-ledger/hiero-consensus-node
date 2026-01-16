// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V0640PlatformStateSchema;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A service that provides the schema for the platform state, used by {@link State}
 * to implement accessors to the platform state.
 */
public enum PlatformStateService implements Service {
    PLATFORM_STATE_SERVICE;

    /**
     * Temporary access to a function that computes an application version from config.
     */
    private static final AtomicReference<Function<Configuration, SemanticVersion>> APP_VERSION_FN =
            new AtomicReference<>();
    /**
     * The schemas to register with the {@link SchemaRegistry}.
     */
    private static final Collection<Schema> SCHEMAS =
            List.of(new V0540PlatformStateSchema(), new V0640PlatformStateSchema());

    public static final String NAME = "PlatformStateService";

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        SCHEMAS.forEach(registry::register);
    }

    @Override
    public void doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        final var stateSingleton = writableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID);
        stateSingleton.put(UNINITIALIZED_PLATFORM_STATE);
        final var platformStateStore = new WritablePlatformStateStore(writableStates);
        platformStateStore.bulkUpdate(genesisStateSpec(configuration));
    }

    /**
     * Sets the application version to the given version.
     *
     * @param appVersionFn the version to set as the application version
     */
    public void setAppVersionFn(@NonNull final Function<Configuration, SemanticVersion> appVersionFn) {
        APP_VERSION_FN.set(requireNonNull(appVersionFn));
    }

    private Consumer<PlatformStateModifier> genesisStateSpec(@NonNull final Configuration configuration) {
        return v -> {
            v.setCreationSoftwareVersion(APP_VERSION_FN.get().apply(configuration));
            v.setRound(0);
            v.setLegacyRunningEventHash(null);
            v.setConsensusTimestamp(Instant.EPOCH);
        };
    }
}
