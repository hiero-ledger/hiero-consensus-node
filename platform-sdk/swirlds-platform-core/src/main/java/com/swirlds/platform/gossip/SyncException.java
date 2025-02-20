// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import com.swirlds.platform.network.Connection;

/**
 * Thrown if any issue occurs during a sync that is not connection related
 */
public class SyncException extends Exception {
    public SyncException(final Connection connection, final String message, final Throwable cause) {
        super(connection.getDescription() + " " + message, cause);
    }

    public SyncException(final Connection connection, final String message) {
        this(connection, message, null);
    }

    public SyncException(final String message) {
        super(message);
    }
}
