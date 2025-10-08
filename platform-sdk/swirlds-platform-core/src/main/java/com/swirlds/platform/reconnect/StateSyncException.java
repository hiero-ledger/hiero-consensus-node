// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

/**
 * This exception is thrown if there is a failure during reconnect.
 */
public class StateSyncException extends RuntimeException {

    public StateSyncException(final String message) {
        super(message);
    }

    public StateSyncException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public StateSyncException(final Throwable cause) {
        super(cause);
    }
}
