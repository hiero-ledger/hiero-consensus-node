// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hashlogger;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

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
