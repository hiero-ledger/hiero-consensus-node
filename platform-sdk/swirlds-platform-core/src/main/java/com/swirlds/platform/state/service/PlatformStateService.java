// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * A service that provides the schema for the platform state, used by {@link State}
 * to implement accessors to the platform state.
 */
public class PlatformStateService implements Service {
    public static final int PLATFORM_MIGRATION_ORDER = 0;

    private final Function<Configuration, SemanticVersion> appVersionFn;

    /**
     * The schemas to register with the {@link SchemaRegistry}.
     */
    private static final Collection<Schema> SCHEMAS = List.of(new V0540PlatformStateSchema());

    public static final String NAME = "PlatformStateService";

    public PlatformStateService(@NonNull final Function<Configuration, SemanticVersion> appVersionFn) {
        this.appVersionFn = requireNonNull(appVersionFn);
    }

    @Override
    public int migrationOrder() {
        return PLATFORM_MIGRATION_ORDER;
    }

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
    public boolean doGenesisSetup(@NonNull WritableStates writableStates, @NonNull Configuration configuration) {
        writableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID).put(UNINITIALIZED_PLATFORM_STATE);
        final var platformStateStore = new WritablePlatformStateStore(writableStates);
        platformStateStore.bulkUpdate(spec -> {
            spec.setCreationSoftwareVersion(appVersionFn.apply(configuration));
            spec.setRound(0);
        });
        return true;
    }
}
