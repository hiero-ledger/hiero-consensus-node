// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.communication.multithreaded;

import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.Connection;
import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Wraps another connection, but returns true for {@link #connected()} only the specified number of times
 */
public class ExpiringConnection implements Connection {
    private final Connection connection;
    private final AtomicInteger returnConnectedTimes;

    public ExpiringConnection(final Connection connection, final int returnConnectedTimes) {
        this.connection = connection;
        this.returnConnectedTimes = new AtomicInteger(returnConnectedTimes);
    }

    @Override
    public void disconnect() {
        connection.disconnect();
    }

    @Override
    public NodeId getSelfId() {
        return connection.getSelfId();
    }

    @Override
    public NodeId getOtherId() {
        return connection.getOtherId();
    }

    @Override
    public SyncInputStream getDis() {
        return connection.getDis();
    }

    @Override
    public SyncOutputStream getDos() {
        return connection.getDos();
    }

    @Override
    public boolean connected() {
        return returnConnectedTimes.getAndDecrement() > 0;
    }

    @Override
    public int getTimeout() throws SocketException {
        return connection.getTimeout();
    }

    @Override
    public void setTimeout(final long timeoutMillis) throws SocketException {
        connection.setTimeout(timeoutMillis);
    }

    @Override
    public void initForSync() throws IOException {
        connection.initForSync();
    }

    @Override
    public boolean isOutbound() {
        return connection.isOutbound();
    }

    @Override
    public String getDescription() {
        return connection.getDescription();
    }
}
