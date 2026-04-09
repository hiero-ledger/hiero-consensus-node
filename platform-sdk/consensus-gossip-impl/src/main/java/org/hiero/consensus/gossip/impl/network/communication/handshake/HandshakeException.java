// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.communication.handshake;

import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;

/**
 * Thrown when a handshake fails on a new connection
 */
public class HandshakeException extends NetworkProtocolException {
    public HandshakeException(final String message) {
        super(message);
    }
}
