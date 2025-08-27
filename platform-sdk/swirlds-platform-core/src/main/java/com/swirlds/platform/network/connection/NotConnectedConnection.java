// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connection;

import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.Connection;
import java.net.SocketException;
import org.hiero.consensus.model.node.NodeId;

/**
 * An implementation of {@link Connection} that is used to avoid returning null if there is no connection. This
 * connection will never be connected and will do nothing on disconnect. All other methods will throw an exception.
 */
public class NotConnectedConnection implements Connection {
    private static final Connection SINGLETON = new NotConnectedConnection();

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
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public NodeId getOtherId() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public SyncInputStream getDis() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public SyncOutputStream getDos() {
        throw new UnsupportedOperationException("Not implemented");
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
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     */
    @Override
    public void setTimeout(long timeoutMillis) throws SocketException {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     */
    @Override
    public void initForSync() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Throws an {@link UnsupportedOperationException} since this is not a real connection
     *
     * @return never returns, always throws
     */
    @Override
    public boolean isOutbound() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getDescription() {
        return "NotConnectedConnection";
    }
}
