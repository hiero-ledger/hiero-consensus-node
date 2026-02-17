// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static org.hiero.otter.fixtures.app.state.OtterStateInitializer.initOtterAppState;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.roster.RosterStateUtils;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * Utility methods for creating and manipulating Otter application state.
 */
public final class OtterStateUtils {

    private OtterStateUtils() {}

    /**
     * Creates an initialized {@code OtterAppState}.
     *
     * @param configuration   the platform configuration instance to use when creating the new instance of state
     * @param metrics         the platform metric instance to use when creating the new instance of state
     * @param roster          the initial roster stored in the state
     * @param version         the software version to set in the state
     * @param services        the services to initialize
     * @return state root
     */
    @NonNull
    public static VirtualMapStateImpl createGenesisState(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Roster roster,
            @NonNull final SemanticVersion version,
            @NonNull final List<OtterService> services) {

        final VirtualMapStateImpl state = new VirtualMapStateImpl(configuration, metrics);

        initOtterAppState(state, services);

        // set up the state's default values for this service
        for (final OtterService service : services) {
            final OtterServiceStateSpecification specification = service.stateSpecification();
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
    public static void commitState(@NonNull final VirtualMapStateImpl virtualMapState) {
        virtualMapState.getServices().keySet().stream()
                .map(virtualMapState::getWritableStates)
                .map(writableStates -> (CommittableWritableStates) writableStates)
                .forEach(CommittableWritableStates::commit);
    }
}
