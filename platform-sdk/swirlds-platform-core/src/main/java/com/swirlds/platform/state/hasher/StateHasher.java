// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hasher;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Hashes signed states
 */
@FunctionalInterface
public interface StateHasher {
    /**
     * Hashes a SignedState.
     *
     * @param unhashedState the state to hash
     * @return the same state and round, with the state hashed
     */
    @InputWireLabel("unhashed state with hash complexity")
    @Nullable
    ReservedSignedState hashState(@NonNull StateWithHashComplexity unhashedState);
}
