// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.communication.states;

import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.hiero.consensus.gossip.impl.network.communication.NegotiationException;

/**
 * Sleep and end the negotiation
 */
public class Sleep implements NegotiationState {
    private final int sleepMillis;
    private final String description;

    public Sleep(final int sleepMillis) {
        this.sleepMillis = sleepMillis;
        this.description = "slept for " + sleepMillis + " ms";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NegotiationState transition() throws NegotiationException, NetworkProtocolException, InterruptedException {
        Thread.sleep(sleepMillis);
        return null;
    }

    @Override
    public String getLastTransitionDescription() {
        return description;
    }
}
