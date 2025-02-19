/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.network.communication.states;

import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.communication.NegotiationException;
import com.swirlds.platform.network.protocol.PeerProtocol;
import java.io.IOException;

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
