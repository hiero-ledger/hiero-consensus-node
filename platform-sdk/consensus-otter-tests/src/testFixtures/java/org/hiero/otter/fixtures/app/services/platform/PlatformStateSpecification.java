// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.platform;

import static org.hiero.otter.fixtures.app.state.OtterStateId.PLATFORM_STATE_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * This class defines the state specification for the Platform service.
 */
public class PlatformStateSpecification implements OtterServiceStateSpecification {

    private static final int STATE_ID = PLATFORM_STATE_STATE_ID.id();
    private static final String STATE_KEY = "PLATFORM_STATE";

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<StateDefinition<?, ?>> statesToCreate() {
        return Set.of(StateDefinition.singleton(STATE_ID, STATE_KEY, PlatformState.PROTOBUF));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        final WritableSingletonState<PlatformState> singletonState = states.getSingleton(STATE_ID);
        if (singletonState.get() == null) {
            final ConsensusSnapshot consensusSnapshot = ConsensusSnapshot.newBuilder()
                    .consensusTimestamp(Timestamp.DEFAULT)
                    .build();
            final PlatformState platformState = PlatformState.newBuilder()
                    .consensusSnapshot(consensusSnapshot)
                    .creationSoftwareVersion(version)
                    .build();
            singletonState.put(platformState);
        }
    }
}
