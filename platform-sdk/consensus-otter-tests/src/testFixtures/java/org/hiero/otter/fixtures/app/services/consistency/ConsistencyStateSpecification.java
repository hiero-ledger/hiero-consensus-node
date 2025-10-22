// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static org.hiero.otter.fixtures.app.state.OtterStateId.CONSISTENCY_SINGLETON_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.model.ConsistencyState;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * This class defines the state specification for the Consistency service.
 */
public class ConsistencyStateSpecification implements OtterServiceStateSpecification {

    private static final int STATE_ID = CONSISTENCY_SINGLETON_STATE_ID.id();
    private static final String STATE_KEY = "CONSISTENCY_STATE";

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<StateDefinition<?, ?>> statesToCreate() {
        return Set.of(StateDefinition.singleton(STATE_ID, STATE_KEY, ConsistencyState.PROTOBUF));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        final WritableSingletonState<ConsistencyState> consistencyState = states.getSingleton(STATE_ID);
        if (consistencyState.get() == null) {
            consistencyState.put(ConsistencyState.DEFAULT);
        }
    }
}
