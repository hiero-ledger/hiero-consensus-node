/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.network;

import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import com.swirlds.common.threading.locks.AutoClosableResourceLock;
import com.swirlds.common.threading.locks.Locks;
import com.swirlds.common.threading.locks.locked.LockedResource;
import com.swirlds.platform.network.connection.NotConnectedConnection;
import java.util.concurrent.locks.Condition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages a connection that is initiated by the peer. If a new connection is established by the peer, the previous one
 * will be closed.
 *
 * Calls to the method {@link #newConnection(Connection)} are thread safe. Calls to {@link #waitForConnection()} and
 * {@link #getConnection()} are not. It is assumed that these methods will either be always called by the same thread, or
 * there should be some external synchronization on them.
 */
public class InboundConnectionManager implements ConnectionManager {
    private static final Logger logger = LogManager.getLogger(InboundConnectionManager.class);
    /** the current connection in use, initially not connected. there is no synchronization on this variable */
    private Connection currentConn = NotConnectedConnection.getSingleton();
    /** locks the connection managed by this instance */
    private final AutoClosableResourceLock<Connection> lock = Locks.createResourceLock(currentConn);
    /** condition to wait on for a new connection */
    private final Condition newConnection = lock.newCondition();

    /**
     * {@inheritDoc}
     */
    @Override
    public Connection waitForConnection() throws InterruptedException {
        if (!currentConn.connected()) {
            currentConn = waitForNewConnection();
        }
        return currentConn;
    }

    /**
     * Returns the current connection, does not wait for a new one if it's broken.
     *
     * This method is not thread safe
     *
     * @return the current connection
     */
    @Override
    public Connection getConnection() {
        return currentConn;
    }

    /**
     * Waits for a new connection until it's supplied via the {@link #newConnection(Connection)} method
     *
     * @return the new connection
     * @throws InterruptedException
     * 		if interrupted while waiting
     */
    private Connection waitForNewConnection() throws InterruptedException {
        try (final LockedResource<Connection> lockedConn = lock.lock()) {
            while (!lockedConn.getResource().connected()) {
                newConnection.await();
            }
            return lockedConn.getResource();
        }
    }

    /**
     * Provides a new connection initiated by the peer. If a peer establishes a new connection, we assume the previous
     * one is broken, so we close and discard it.
     *
     * Note: The thread using this manager will not accept a new connection while it still has a valid one. This is
     * another reason why this method has to close any connection that is currently open.
     *
     * @param connection
     * 		a new connection
     * @throws InterruptedException
     * 		if the thread is interrupted while acquiring the lock
     */
    @Override
    public void newConnection(final Connection connection) throws InterruptedException {
        try (final LockedResource<Connection> lockedConn = lock.lockInterruptibly()) {
            final Connection old = lockedConn.getResource();
            if (old.connected()) {
                logger.warn(
                        SOCKET_EXCEPTIONS.getMarker(),
                        "{} got new connection from {}, disconnecting old one",
                        old.getSelfId(),
                        old.getOtherId());
            }
            old.disconnect();
            lockedConn.setResource(connection);
            newConnection.signalAll();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isOutbound() {
        return false;
    }
}
