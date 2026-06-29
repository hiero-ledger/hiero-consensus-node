// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.management.hashing;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * This component is responsible for logging the hash of the state each round (for debugging).
 */
public interface HashLogger {

    /**
     * Log the hashes of the state.
     *
     * @param reservedState the state to retrieve hash information from and log.
     */
    @InputWireLabel("hashed states to log")
    void logHashes(@NonNull ReservedSignedState reservedState);
}
