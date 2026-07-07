// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.roster.WritableRosterStore;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * Utility methods for creating and manipulating Otter application state.
 */
public final class OtterStateUtils {

    private OtterStateUtils() {}

    /**
     * Creates an initialized {@code OtterAppState}.
     *
     * @param roster          the initial roster stored in the state
     * @param version         the software version to set in the state
     * @param services        the services to initialize
     * @return state root
     */
    @NonNull
    public static VirtualMapState initGenesisState(
            @NonNull final VirtualMapState state,
            @NonNull final Roster roster,
            @NonNull final SemanticVersion version,
            @NonNull final List<OtterService> services) {

        initOtterAppStateStructure(state, services);

        // set up the state's default values for this service
        for (final OtterService service : services) {
            final OtterServiceStateSpecification specification = service.stateSpecification();
            specification.setDefaultValues(state.getWritableStates(service.name()), version);
        }
        final WritableRosterStore rosterStore =
                new WritableRosterStore(state.getWritableStates(RosterStateId.SERVICE_NAME));
        rosterStore.putActiveRoster(roster, 0L);
        commitState(state);

        return state;
    }

    /**
     * Commit the state of all services.
     *
     * @param virtualMapState the virtual map state containing the services to commit
     */
    public static void commitState(@NonNull final VirtualMapState virtualMapState) {
        ((VirtualMapStateImpl) virtualMapState)
                .getServices().keySet().stream()
                        .map(virtualMapState::getWritableStates)
                        .map(writableStates -> (CommittableWritableStates) writableStates)
                        .forEach(CommittableWritableStates::commit);
    }

    /**
     * Initialize the state structure for the OtterApp.
     *
     * @param state the state to initialize
     * @param services the services to initialize
     */
    public static void initOtterAppStateStructure(
            @NonNull final VirtualMapState state, @NonNull final List<OtterService> services) {
        for (final OtterService service : services) {
            final OtterServiceStateSpecification specification = service.stateSpecification();
            for (final StateDefinition<?, ?> stateDefinition : specification.statesToCreate()) {
                // the metadata associates the state definition with the service
                final StateMetadata<?, ?> stateMetadata = new StateMetadata<>(service.name(), stateDefinition);
                state.initializeState(stateMetadata);
            }
        }
        commitState(state);
    }
}
