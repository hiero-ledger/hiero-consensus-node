// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.output.StateChanges.Builder;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.hiero.consensus.roster.RosterStateId;

/**
 * This class is used to initialize the state of test applications. It allows to register the necessary
 * constructables and initializes the platform and roster states.
 */
public final class TestingAppStateInitializer {

    private TestingAppStateInitializer() {}

    /**
     * Initialize the states for the given {@link MerkleNodeState}. This method will initialize both the
     * platform and roster states.
     *
     * @param state the state to initialize
     * @param configuration configuration to use
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public static List<Builder> initConsensusModuleStates(
            @NonNull final MerkleNodeState state, @NonNull final Configuration configuration) {
        List<Builder> list = new ArrayList<>();
        list.addAll(initPlatformState(state));
        list.addAll(initRosterState(state, configuration));
        return list;
    }

    /**
     * Initialize the platform state for the given {@link MerkleNodeState}. This method will initialize the
     * states used by the {@link PlatformStateService}.
     *
     * @param state the state to initialize
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public static List<Builder> initPlatformState(@NonNull final MerkleNodeState state) {
        final var schema = new V0540PlatformStateSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    final var md = new StateMetadata<>(PlatformStateService.NAME, def);
                    if (def.singleton()) {
                        state.initializeState(md);
                    } else {
                        throw new IllegalStateException("PlatformStateService only expected to use singleton states");
                    }
                });
        final var mockMigrationContext = mock(MigrationContext.class);
        final var writableStates = state.getWritableStates(PlatformStateService.NAME);
        schema.migrate(mockMigrationContext);
        writableStates
                .<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID)
                .put(V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE);
        ((CommittableWritableStates) writableStates).commit();
        return Collections.emptyList();
    }

    /**
     * Initialize the roster state for the given {@link MerkleNodeState}. This method will initialize the
     * states used by the {@code RosterService}.
     *
     * @param state the state to initialize
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public static List<Builder> initRosterState(
            @NonNull final MerkleNodeState state, @NonNull final Configuration configuration) {
        final var schema = new V0540RosterBaseSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    final var md = new StateMetadata<>(RosterStateId.SERVICE_NAME, def);
                    if (def.singleton() || def.onDisk()) {
                        state.initializeState(md);
                    } else {
                        throw new IllegalStateException(
                                "RosterService only expected to use singleton and onDisk virtual map states");
                    }
                });
        final var mockMigrationContext = mock(MigrationContext.class);
        final var writableStates = state.getWritableStates(RosterStateId.SERVICE_NAME);
        schema.migrate(mockMigrationContext);
        ((CommittableWritableStates) writableStates).commit();
        return Collections.emptyList();
    }
}
