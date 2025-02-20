// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

/**
 * Thrown whenever a non IO exception happens during a network protocol execution
 */
public class NetworkProtocolException extends Exception {
    public NetworkProtocolException(final Throwable cause) {
        super(cause);
    }

    public NetworkProtocolException(final String message) {
        super(message);
    }
}
