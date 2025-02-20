// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.states;

import static com.swirlds.platform.network.communication.NegotiatorBytes.ACCEPT;
import static com.swirlds.platform.network.communication.NegotiatorBytes.KEEPALIVE;
import static com.swirlds.platform.network.communication.NegotiatorBytes.REJECT;

import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.communication.NegotiationException;
import com.swirlds.platform.network.communication.NegotiatorBytes;
import java.io.IOException;
import java.io.InputStream;

/**
 * We have already sent a keepalive, this state waits for, and handles, the byte sent by the peer in parallel
 */
public class SentKeepalive extends NegotiationStateWithDescription {
    private final InputStream byteInput;

    private final NegotiationState sleep;
    private final ReceivedInitiate receivedInitiate;

    /**
     * @param byteInput
     * 		the stream to read from
     * @param sleep
     * 		the sleep state to transition to if the peers also sends a keepalive
     * @param receivedInitiate
     * 		the state to transition to if the peer sends an initiate
     */
    public SentKeepalive(
            final InputStream byteInput, final NegotiationState sleep, final ReceivedInitiate receivedInitiate) {
        this.byteInput = byteInput;
        this.sleep = sleep;
        this.receivedInitiate = receivedInitiate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NegotiationState transition()
            throws NegotiationException, NetworkProtocolException, InterruptedException, IOException {
        final int b = byteInput.read();
        NegotiatorBytes.checkByte(b);
        return switch (b) {
            case KEEPALIVE -> {
                setDescription("both sent keepalive");
                yield sleep;
            } // we both sent keepalive
            case ACCEPT, REJECT -> throw new NegotiationException("Unexpected ACCEPT or REJECT");
            default -> {
                setDescription("received initiate - " + b);
                yield receivedInitiate.receivedInitiate(b);
            }
        };
    }
}
