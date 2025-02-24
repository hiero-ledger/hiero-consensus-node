// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.states;

import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.communication.NegotiationException;
import java.io.IOException;

/**
 * Represents a single state in a negotiation state machine
 */
@FunctionalInterface
public interface NegotiationState {
    /**
     * Transitions to the next negotiation state
     *
     * @return the next state, or null if the negotiation ended
     * @throws NegotiationException
     * 		if an issue occurs during negotiation
     * @throws NetworkProtocolException
     * 		if a protocol is negotiated and issue occurs while running it
     * @throws InterruptedException
     * 		if the thread running this is interrupted
     * @throws IOException
     * 		if an IO error occurs with the connection used
     */
    NegotiationState transition()
            throws NegotiationException, NetworkProtocolException, InterruptedException, IOException;

    /**
     * @return a human-readable description of the last transition this state was involved in
     */
    default String getLastTransitionDescription() {
        return "NO DESCRIPTION";
    }
}
