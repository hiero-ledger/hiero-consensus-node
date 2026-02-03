// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.communication.states;

import java.io.IOException;
import java.io.OutputStream;
import org.hiero.consensus.gossip.impl.network.communication.NegotiationProtocols;
import org.hiero.consensus.gossip.impl.network.communication.NegotiatorBytes;

/**
 * Sends a KEEPALIVE or a protocol ID initiating that protocol
 */
public class InitialState extends NegotiationStateWithDescription {
    private final NegotiationProtocols protocols;
    private final OutputStream byteOutput;

    private final SentKeepalive stateSentKeepalive;
    private final SentInitiate stateSentInitiate;

    /**
     * @param protocols
     * 		protocols being negotiated
     * @param byteOutput
     * 		the stream to write to
     * @param stateSentKeepalive
     * 		the state to transition to if we send a keepalive
     * @param stateSentInitiate
     * 		the state to transition to if we initiate a protocol
     */
    public InitialState(
            final NegotiationProtocols protocols,
            final OutputStream byteOutput,
            final SentKeepalive stateSentKeepalive,
            final SentInitiate stateSentInitiate) {
        this.protocols = protocols;
        this.byteOutput = byteOutput;
        this.stateSentKeepalive = stateSentKeepalive;
        this.stateSentInitiate = stateSentInitiate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NegotiationState transition() throws IOException {
        final byte protocolByte = protocols.initiateProtocol();

        if (protocolByte >= 0) {
            byteOutput.write(protocolByte);
            byteOutput.flush();
            setDescription(
                    "initiated protocol " + protocols.getInitiatedProtocol().getProtocolName());
            return stateSentInitiate.initiatedProtocol(protocolByte);
        } else {
            byteOutput.write(NegotiatorBytes.KEEPALIVE);
            byteOutput.flush();
            setDescription("sent keepalive");
            return stateSentKeepalive;
        }
    }
}
