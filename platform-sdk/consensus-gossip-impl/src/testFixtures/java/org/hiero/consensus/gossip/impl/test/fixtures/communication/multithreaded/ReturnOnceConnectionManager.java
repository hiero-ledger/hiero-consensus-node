// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.test.fixtures.communication.multithreaded;

import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.ConnectionManager;

/**
 * A connection manager that returns a connection once after which it throws an interrupted exception every time
 */
public class ReturnOnceConnectionManager implements ConnectionManager {
    final Connection connection;
    private volatile boolean connectionReturned = false;

    public ReturnOnceConnectionManager(final Connection connection) {
        this.connection = connection;
    }

    @Override
    public Connection waitForConnection() throws InterruptedException {
        if (connectionReturned) {
            throw new InterruptedException();
        }
        connectionReturned = true;
        return connection;
    }

    @Override
    public Connection getConnection() {
        throw new IllegalStateException("unsupported");
    }

    @Override
    public void newConnection(final Connection connection) {
        throw new IllegalStateException("unsupported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isOutbound() {
        return connection.isOutbound();
    }
}
