// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.communication.states;

import java.io.IOException;
import java.io.InputStream;
import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.hiero.consensus.gossip.impl.network.communication.NegotiationException;
import org.hiero.consensus.gossip.impl.network.communication.NegotiationProtocols;
import org.hiero.consensus.gossip.impl.network.communication.NegotiatorBytes;

/**
 * Waits for, and handles, an ACCEPT or REJECT to a protocol initiated by us
 */
public class WaitForAcceptReject extends NegotiationStateWithDescription {
    private final NegotiationProtocols protocols;
    private final InputStream byteInput;

    private final ProtocolNegotiated negotiated;
    private final NegotiationState sleep;

    /**
     * @param protocols
     * 		protocols being negotiated
     * @param byteInput
     * 		the stream to read from
     * @param negotiated
     * 		the state to transition to if a protocol gets accepted
     * @param sleep
     * 		the sleep state to transition to if the protocol gets rejected
     */
    public WaitForAcceptReject(
            final NegotiationProtocols protocols,
            final InputStream byteInput,
            final ProtocolNegotiated negotiated,
            final NegotiationState sleep) {
        this.protocols = protocols;
        this.byteInput = byteInput;
        this.negotiated = negotiated;
        this.sleep = sleep;
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
            case NegotiatorBytes.ACCEPT -> {
                setDescription("received accept, running protocol");
                yield negotiated.runProtocol(protocols.initiateAccepted());
            }
            case NegotiatorBytes.REJECT -> {
                // peer declined, so initiate failed
                protocols.initiateFailed();
                setDescription("received reject, sleeping");
                yield sleep;
            }
            default ->
                throw new NegotiationException(String.format("Unexpected byte %d, expected ACCEPT or REJECT", b));
        };
    }
}
