// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.states;

import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.communication.NegotiationException;
import com.swirlds.platform.network.communication.NegotiationProtocols;
import com.swirlds.platform.network.communication.NegotiatorBytes;
import com.swirlds.platform.network.protocol.PeerProtocol;
import java.io.IOException;
import java.io.InputStream;

/**
 * A protocol initiate was sent, this state waits for and handles the byte sent by the peer in parallel
 */
public class SentInitiate extends NegotiationStateWithDescription {
    private final NegotiationProtocols protocols;
    private final InputStream byteInput;

    private final ProtocolNegotiated negotiated;
    private final ReceivedInitiate receivedInitiate;
    private final WaitForAcceptReject waitForAcceptReject;
    private final NegotiationState sleep;

    private int protocolInitiated = NegotiatorBytes.UNINITIALIZED;

    /**
     * @param protocols
     * 		protocols being negotiated
     * @param byteInput
     * 		the stream to read from
     * @param negotiated
     * 		the state to transition to if a protocol gets negotiated
     * @param receivedInitiate
     * 		the state to transition to if we need to reply to the peer's initiate
     * @param waitForAcceptReject
     * 		the state to transition to if the peer needs to reply to our initiate
     * @param sleep
     * 		the sleep state to transition to if the negotiation fails
     */
    public SentInitiate(
            final NegotiationProtocols protocols,
            final InputStream byteInput,
            final ProtocolNegotiated negotiated,
            final ReceivedInitiate receivedInitiate,
            final WaitForAcceptReject waitForAcceptReject,
            final NegotiationState sleep) {
        this.protocols = protocols;
        this.byteInput = byteInput;
        this.negotiated = negotiated;
        this.receivedInitiate = receivedInitiate;
        this.waitForAcceptReject = waitForAcceptReject;
        this.sleep = sleep;
    }

    /**
     * Set the protocol ID that was initiated by us
     *
     * @param protocolId
     * 		the ID of the protocol initiated
     * @return this state
     */
    public NegotiationState initiatedProtocol(final byte protocolId) {
        protocolInitiated = protocolId;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NegotiationState transition()
            throws NegotiationException, NetworkProtocolException, InterruptedException, IOException {
        final int b = byteInput.read();
        NegotiatorBytes.checkByte(b);
        final NegotiationState next = transition(b);
        protocolInitiated = NegotiatorBytes.UNINITIALIZED;
        return next;
    }

    private NegotiationState transition(final int b) {
        if (b == NegotiatorBytes.KEEPALIVE) {
            setDescription("waiting for accept or reject from the peer");
            // we wait for ACCEPT or reject
            return waitForAcceptReject;
        }
        if (b == protocolInitiated) { // both initiated the same protocol at the same time
            final PeerProtocol peerProtocol = protocols.getInitiatedProtocol();
            if (peerProtocol.acceptOnSimultaneousInitiate()) {
                setDescription("peer initiated the same protocol, running " + peerProtocol.getProtocolName());
                return negotiated.runProtocol(protocols.initiateAccepted());
            } else {
                setDescription("peer initiated the same protocol, the protocol will not run - "
                        + peerProtocol.getProtocolName());
                protocols.initiateFailed();
                return sleep;
            }
        }
        // peer initiated a different protocol
        if (b < protocolInitiated) { // lower index means higher priority
            // the one we initiated failed
            protocols.initiateFailed();
            setDescription("peer initiated a higher priority protocol - " + b);
            // THEIR protocol is higher priority, so we should ACCEPT or REJECT
            return receivedInitiate.receivedInitiate(b);
        } else {
            setDescription("we initiated a higher priority protocol than the peer - " + protocolInitiated);
            // OUR protocol is higher priority, so they should ACCEPT or REJECT
            return waitForAcceptReject;
        }
    }
}
