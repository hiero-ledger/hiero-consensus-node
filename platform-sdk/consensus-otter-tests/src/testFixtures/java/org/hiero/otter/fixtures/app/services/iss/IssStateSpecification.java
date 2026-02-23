// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.iss;

import static org.hiero.otter.fixtures.app.state.OtterStateId.ISS_SINGLETON_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.model.IssState;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * This class defines the state specification for the ISS service.
 */
public class IssStateSpecification implements OtterServiceStateSpecification {

    private static final int STATE_ID = ISS_SINGLETON_STATE_ID.id();
    private static final String STATE_KEY = "ISS_STATE";

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<StateDefinition<?, ?>> statesToCreate() {
        return Set.of(StateDefinition.singleton(STATE_ID, STATE_KEY, IssState.PROTOBUF));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        final WritableSingletonState<IssState> issState = states.getSingleton(STATE_ID);
        if (issState.get() == null) {
            issState.put(IssState.DEFAULT);
        }
    }
}
