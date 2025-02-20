// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

/**
 * This exception is thrown if there is a failure during reconnect.
 */
public class ReconnectException extends RuntimeException {

    public ReconnectException(final String message) {
        super(message);
    }

    public ReconnectException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ReconnectException(final Throwable cause) {
        super(cause);
    }
}
