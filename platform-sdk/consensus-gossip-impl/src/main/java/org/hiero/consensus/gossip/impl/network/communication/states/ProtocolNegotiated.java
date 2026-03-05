// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.communication.states;

import java.io.IOException;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.hiero.consensus.gossip.impl.network.communication.NegotiationException;
import org.hiero.consensus.gossip.impl.network.protocol.PeerProtocol;

/**
 * Runs a protocol previously negotiated
 */
public class ProtocolNegotiated extends NegotiationStateWithDescription {
    private final Connection connection;
    private PeerProtocol peerProtocol;

    /**
     * @param connection
     * 		the connection to run the protocol on
     */
    public ProtocolNegotiated(final Connection connection) {
        this.connection = connection;
    }

    /**
     * Set the protocol to run on the next transition
     *
     * @param peerProtocol
     * 		the protocol to run
     * @return this state
     */
    public NegotiationState runProtocol(final PeerProtocol peerProtocol) {
        this.peerProtocol = peerProtocol;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NegotiationState transition()
            throws NegotiationException, NetworkProtocolException, IOException, InterruptedException {
        if (peerProtocol == null) {
            throw new IllegalStateException("Cannot run a protocol because it is null");
        }
        try {
            peerProtocol.runProtocol(connection);
        } finally {
            setDescription("ran protocol " + peerProtocol.getProtocolName());
            peerProtocol = null;
        }
        return null; // back to initial state
    }
}
