// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

/**
 * This exception is thrown if there is a failure during reconnect.
 */
public class ReconnectStateSyncException extends RuntimeException {

    public ReconnectStateSyncException(final String message) {
        super(message);
    }

    public ReconnectStateSyncException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ReconnectStateSyncException(final Throwable cause) {
        super(cause);
    }
}
