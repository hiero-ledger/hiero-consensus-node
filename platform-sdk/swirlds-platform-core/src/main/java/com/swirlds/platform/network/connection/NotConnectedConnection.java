// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connection;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.Connection;
import java.net.SocketException;

/**
 * An implementation of {@link Connection} that is used to avoid returning null if there is no connection.
 * This connection will never be connected and will do nothing on disconnect. All other methods will throw an
 * exception.
 */
public class NotConnectedConnection implements Connection {
    private static final Connection SINGLETON = new NotConnectedConnection();
    private static final UnsupportedOperationException NOT_IMPLEMENTED =
            new UnsupportedOperationException("Not implemented");

    public static Connection getSingleton() {
        return SINGLETON;
    }

    /**
     * Does nothing since its not a real connection
     */
    @Override
    public void disconnect() {
        // nothing to do
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public NodeId getSelfId() {
        throw NOT_IMPLEMENTED;
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public NodeId getOtherId() {
        throw NOT_IMPLEMENTED;
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public SyncInputStream getDis() {
        throw NOT_IMPLEMENTED;
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public SyncOutputStream getDos() {
        throw NOT_IMPLEMENTED;
    }

    /**
     * @return always returns false
     */
    @Override
    public boolean connected() {
        return false;
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public int getTimeout() throws SocketException {
        throw NOT_IMPLEMENTED;
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     */
    @Override
    public void setTimeout(long timeoutMillis) throws SocketException {
        throw NOT_IMPLEMENTED;
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     */
    @Override
    public void initForSync() {
        throw NOT_IMPLEMENTED;
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public boolean isOutbound() {
        throw NOT_IMPLEMENTED;
    }

    @Override
    public String getDescription() {
        return "NotConnectedConnection";
    }
}
