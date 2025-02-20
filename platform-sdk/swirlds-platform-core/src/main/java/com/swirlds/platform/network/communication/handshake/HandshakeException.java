// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.handshake;

import com.swirlds.platform.network.NetworkProtocolException;

/**
 * Thrown when a handshake fails on a new connection
 */
public class HandshakeException extends NetworkProtocolException {
    public HandshakeException(final String message) {
        super(message);
    }
}
