// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.hashing;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.StateWithHashComplexity;
import org.hiero.consensus.wiring.framework.component.InputWireLabel;

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
