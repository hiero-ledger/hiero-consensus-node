// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hasher;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.wiring.components.StateAndRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Hashes signed states
 */
@FunctionalInterface
public interface StateHasher {
    /**
     * Hashes a SignedState.
     *
     * @param stateAndRound the state and round, which contains the state to hash
     * @return the same state and round, with the state hashed
     */
    @InputWireLabel("unhashed state and round")
    @Nullable
    StateAndRound hashState(@NonNull StateAndRound stateAndRound);
}
