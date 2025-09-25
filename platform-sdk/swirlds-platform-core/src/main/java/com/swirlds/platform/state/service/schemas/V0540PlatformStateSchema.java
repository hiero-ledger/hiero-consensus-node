// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service.schemas;

import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Defines the {@link PlatformState} singleton and initializes it at genesis.
 */
public class V0540PlatformStateSchema extends Schema {

    private static final Function<Configuration, SemanticVersion> UNAVAILABLE_VERSION_FN = config -> {
        throw new IllegalStateException("No version information available");
    };

    public static final String PLATFORM_STATE_KEY = "PLATFORM_STATE";
    // FUTURE WORK: get rid of this dependency on SingletonType
    public static final int PLATFORM_STATE_STATE_ID =
            SingletonType.PLATFORMSTATESERVICE_I_PLATFORM_STATE.protoOrdinal();
    public static final String PLATFORM_STATE_STATE_LABEL = computeLabel(PlatformStateService.NAME, PLATFORM_STATE_KEY);

    /**
     * A platform state to be used as the non-null platform state under any circumstance a genesis state
     * is encountered before initializing the States API.
     */
    public static final PlatformState UNINITIALIZED_PLATFORM_STATE =
            new PlatformState(null, 0, ConsensusSnapshot.DEFAULT, null, null, 0L, Bytes.EMPTY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(54).patch(0).build();

    private final Function<Configuration, SemanticVersion> versionFn;

    public V0540PlatformStateSchema() {
        this(UNAVAILABLE_VERSION_FN);
    }

    public V0540PlatformStateSchema(@NonNull final Function<Configuration, SemanticVersion> versionFn) {
        super(VERSION);
        this.versionFn = requireNonNull(versionFn);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(PLATFORM_STATE_STATE_ID, PLATFORM_STATE_KEY, PlatformState.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var stateSingleton = ctx.newStates().<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID);
        if (ctx.isGenesis()) {
            stateSingleton.put(UNINITIALIZED_PLATFORM_STATE);
            final var platformStateStore = new WritablePlatformStateStore(ctx.newStates());
            platformStateStore.bulkUpdate(genesisStateSpec(ctx));
        } else {
            // (FUTURE) Delete this code path, it is only reached through the Browser entrypoint
            if (stateSingleton.get() == null) {
                stateSingleton.put(UNINITIALIZED_PLATFORM_STATE);
            }
        }
    }

    private Consumer<PlatformStateModifier> genesisStateSpec(@NonNull final MigrationContext ctx) {
        return v -> {
            v.setCreationSoftwareVersion(versionFn.apply(ctx.appConfig()));
            v.setRound(0);
            v.setLegacyRunningEventHash(null);
            v.setConsensusTimestamp(Instant.EPOCH);
            final var basicConfig = ctx.platformConfig().getConfigData(BasicConfig.class);
        };
    }
}
