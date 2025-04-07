// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateValidator;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a way to retrieve new signed state over some network implementation
 */
public interface ReconnectNetworkHelper {
    /**
     * Attempts to receive a new signed state by reconnecting with the specified neighbor.
     *
     * @return the signed state received from the neighbor
     * @throws ReconnectException if any error occurs during the reconnect attempt
     */
    @NonNull
    ReservedSignedState receiveSignedState(SignedStateValidator signedStateValidator) throws InterruptedException;
}
