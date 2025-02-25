// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

/**
 * Tracks all connections that have been opened and closed by the platform
 */
public interface ConnectionTracker {
    /**
     * Notifies the tracker that a new connection has been opened
     *
     * @param connection the connection that was just established
     */
    void newConnectionOpened(final Connection connection);

    /**
     * Notifies the tracker that a connection has been closed
     *
     * @param outbound true if it was an outbound connection (initiated by self)
     * @param connection the connection that was closed.
     */
    void connectionClosed(final boolean outbound, final Connection connection);
}
