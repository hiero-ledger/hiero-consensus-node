// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app;

import static org.hiero.sloth.fixtures.app.state.BenchmarkStateInitializer.initOtterAppState;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.roster.RosterStateUtils;
import org.hiero.sloth.fixtures.app.state.BenchmarkServiceStateSpecification;

/**
 * Utility methods for creating and manipulating sloth application state.
 */
public final class SlothStateUtils {

    private SlothStateUtils() {}

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
            @NonNull final List<SlothService> services) {

        initOtterAppState(state, services);

        // set up the state's default values for this service
        for (final SlothService service : services) {
            final BenchmarkServiceStateSpecification specification = service.stateSpecification();
            specification.setDefaultValues(state.getWritableStates(service.name()), version);
        }
        RosterStateUtils.setActiveRoster(state, roster, 0L);
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
}
