// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static org.mockito.Mockito.mock;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.ConnectionTracker;
import org.hiero.consensus.gossip.impl.network.SocketConnection;
import org.hiero.consensus.model.node.NodeId;

/**
 * An implementation of {@link Connection} for local testing.
 */
public class DummyConnection extends SocketConnection {
    private static final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();

    private final SyncInputStream dis;
    private final SyncOutputStream dos;
    private final Socket socket;

    public DummyConnection(
            @NonNull final Configuration configuration,
            @NonNull final NodeId selfId,
            @NonNull final NodeId otherId,
            @NonNull final SerializableDataInputStream in,
            @NonNull final SerializableDataOutputStream out) {
        this(
                selfId,
                otherId,
                SyncInputStream.createSyncInputStream(configuration, in, 1024 * 8),
                SyncOutputStream.createSyncOutputStream(configuration, out, 1024 * 8),
                mock(Socket.class));
    }

    public DummyConnection(
            @NonNull final Configuration configuration,
            @NonNull final SerializableDataInputStream in,
            @NonNull final SerializableDataOutputStream out) {
        this(
                SyncInputStream.createSyncInputStream(configuration, in, 1024 * 8),
                SyncOutputStream.createSyncOutputStream(configuration, out, 1024 * 8),
                mock(Socket.class));
    }

    public DummyConnection(
            final NodeId selfId,
            final NodeId otherId,
            final SyncInputStream syncInputStream,
            final SyncOutputStream syncOutputStream,
            final Socket socket) {
        super(
                selfId,
                otherId,
                mock(ConnectionTracker.class),
                false,
                socket,
                syncInputStream,
                syncOutputStream,
                configuration);
        this.dis = syncInputStream;
        this.dos = syncOutputStream;
        this.socket = socket;
    }

    public DummyConnection(
            final SyncInputStream syncInputStream, final SyncOutputStream syncOutputStream, final Socket socket) {
        super(
                null,
                null,
                mock(ConnectionTracker.class),
                false,
                socket,
                syncInputStream,
                syncOutputStream,
                configuration);
        this.dis = syncInputStream;
        this.dos = syncOutputStream;
        this.socket = socket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SyncInputStream getDis() {
        return dis;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SyncOutputStream getDos() {
        return dos;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean connected() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Socket getSocket() {
        return socket;
    }

    @Override
    public void setTimeout(final long timeoutMillis) throws SocketException {
        socket.setSoTimeout(timeoutMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) timeoutMillis);
    }

    @Override
    public int getTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    @Override
    public void initForSync() throws IOException {}
}
