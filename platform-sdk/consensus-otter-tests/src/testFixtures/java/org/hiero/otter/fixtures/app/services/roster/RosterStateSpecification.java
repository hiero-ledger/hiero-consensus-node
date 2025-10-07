// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.roster;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;
import org.hiero.otter.fixtures.app.state.OtterStateId;

/**
 * This class defines the state specification for the Platform service.
 */
public class RosterStateSpecification implements OtterServiceStateSpecification {

    private static final int ROSTER_STATE_ID = OtterStateId.ROSTER_STATE_STATE_ID.id();
    private static final String ROSTER_STATE_KEY = "ROSTER_STATE";

    private static final int ROSTERS_STATE_ID = OtterStateId.ROSTERS_STATE_ID.id();
    private static final String ROSTERS_STATE_KEY = "ROSTERS_STATE";

    private static final long MAX_ROSTERS = 1024L;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<StateDefinition<?, ?>> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(ROSTER_STATE_ID, ROSTER_STATE_KEY, RosterState.PROTOBUF),
                StateDefinition.onDisk(
                        ROSTERS_STATE_ID, ROSTERS_STATE_KEY, ProtoBytes.PROTOBUF, Roster.PROTOBUF, MAX_ROSTERS));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        final WritableSingletonState<RosterState> rosterState = states.getSingleton(ROSTER_STATE_ID);
        // On genesis, create a default roster state from the genesis network info
        if (rosterState.get() == null) {
            rosterState.put(RosterState.DEFAULT);
        }
    }
}
