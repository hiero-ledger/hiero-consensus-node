// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.google.common.annotations.VisibleForTesting;

/**
 * Manages a single topological connection, not a single {@link Connection}. This means that if the network topology
 * states that there should be a connection A->B, there will always be a single {@link ConnectionManager}. {@link
 * Connection}s could break and be re-established, but they will always go though this single point.
 */
public interface ConnectionManager {
    /**
     * Wait indefinitely until a connection is available. If there already is a connection, it will return immediately.
     * If there is no connection, or it is broken, wait until it becomes available.
     *
     * @return the connection managed by this instance
     * @throws InterruptedException
     * 		if the thread gets interrupted while waiting
     */
    Connection waitForConnection() throws InterruptedException;

    /**
     * Returns whatever connection is currently available, even if it's broken. This method should never block.
     * It is used only for unit testing, should never be called from production code, as it skips all thread safety
     *
     * @return the connection managed by this instance. can be broken but should never be null
     */
    @VisibleForTesting
    Connection getConnection();

    /**
     * Provides a new connection to this instance initiated by the peer
     *
     * @param connection
     * 		the new connection established
     * @throws InterruptedException
     * 		thrown if the thread is interrupted while handing over the new connection
     */
    void newConnection(final Connection connection) throws InterruptedException;

    /**
     * Returns whenever connection is supposed to be outbound
     *
     * @return true if connection is outbound (we connect to remote node), false if we are listening for incoming connection
     */
    boolean isOutbound();
}
