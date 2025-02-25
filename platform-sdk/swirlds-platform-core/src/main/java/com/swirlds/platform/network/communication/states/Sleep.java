// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.states;

import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.communication.NegotiationException;

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
