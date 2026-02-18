// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

/**
 * This exception is thrown if there is a failure during reconnect.
 */
public class ReconnectStateException extends RuntimeException {

    public ReconnectStateException(final String message) {
        super(message);
    }

    public ReconnectStateException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ReconnectStateException(final Throwable cause) {
        super(cause);
    }
}
