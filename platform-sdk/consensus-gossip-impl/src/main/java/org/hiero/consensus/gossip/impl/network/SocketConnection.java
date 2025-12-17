// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.NETWORK;

import com.swirlds.config.api.Configuration;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.net.ExtendedSocketOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.exceptions.BadIOException;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
import org.hiero.consensus.model.node.NodeId;

/**
 * Manage a single connection with another member, which can be initiated by self or by them. Once the connection is
 * established, it can be used for syncing, and will have heartbeats that keep it alive.
 */
public class SocketConnection implements Connection {
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(SocketConnection.class);

    private final NodeId selfId;
    private final NodeId otherId;
    private final SyncInputStream dis;
    private final SyncOutputStream dos;
    private final Socket socket;
    private final ConnectionTracker connectionTracker;
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final boolean outbound;
    private final String description;
    private final Configuration configuration;
    private final boolean quickAck;

    /**
     * @param connectionTracker tracks open connections
     * @param selfId            the ID number of the local member
     * @param otherId           the ID number of the other member
     * @param outbound          is the connection outbound
     * @param socket            the socket connecting the two members over TCP/IP
     * @param dis               the input stream
     * @param dos               the output stream
     * @param configuration     the configuration from the platform
     */
    protected SocketConnection(
            final NodeId selfId,
            final NodeId otherId,
            final ConnectionTracker connectionTracker,
            final boolean outbound,
            final Socket socket,
            final SyncInputStream dis,
            final SyncOutputStream dos,
            final Configuration configuration) {
        Objects.requireNonNull(socket);
        Objects.requireNonNull(dis);
        Objects.requireNonNull(dos);
        Objects.requireNonNull(configuration);

        this.selfId = selfId;
        this.otherId = otherId;
        this.connectionTracker = connectionTracker;
        this.outbound = outbound;
        this.description = generateDescription();

        this.socket = socket;
        this.dis = dis;
        this.dos = dos;
        this.configuration = configuration;
        this.quickAck = configuration.getConfigData(SocketConfig.class).quickAck();
    }

    /**
     * Creates a new connection instance
     *
     * @param connectionTracker tracks open connections
     * @param selfId            the ID number of the local member
     * @param otherId           the ID number of the other member
     * @param outbound          is the connection outbound
     * @param socket            the socket connecting the two members over TCP/IP
     * @param dis               the input stream
     * @param dos               the output stream
     * @param configuration     the configuration from the platform
     */
    public static SocketConnection create(
            final NodeId selfId,
            final NodeId otherId,
            final ConnectionTracker connectionTracker,
            final boolean outbound,
            final Socket socket,
            final SyncInputStream dis,
            final SyncOutputStream dos,
            final Configuration configuration) {
        final SocketConnection c =
                new SocketConnection(selfId, otherId, connectionTracker, outbound, socket, dis, dos, configuration);
        connectionTracker.newConnectionOpened(c);
        return c;
    }

    @Override
    public NodeId getSelfId() {
        return selfId;
    }

    @Override
    public NodeId getOtherId() {
        return otherId;
    }

    @Override
    public SyncInputStream getDis() {
        return dis;
    }

    @Override
    public SyncOutputStream getDos() {
        return dos;
    }

    public Socket getSocket() {
        return socket;
    }

    @Override
    public int getTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    /**
     * Sets the timeout of the underlying socket using {@link Socket#setSoTimeout(int)}
     *
     * @param timeoutMillis the timeout value to set in milliseconds
     * @throws SocketException if there is an error in the underlying protocol, such as a TCP error.
     */
    @Override
    public void setTimeout(final long timeoutMillis) throws SocketException {
        socket.setSoTimeout(timeoutMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) timeoutMillis);
    }

    /**
     * End this connection by closing the socket and streams
     */
    @Override
    public void disconnect() {
        final boolean wasConnected = connected.getAndSet(false);
        if (wasConnected) {
            // only update when closing an open connection. Not when closing the same twice.
            connectionTracker.connectionClosed(isOutbound(), this);
        }
        logger.debug(NETWORK.getMarker(), "disconnecting connection from {} to {}", selfId, otherId);

        NetworkUtils.close(socket, dis, dos);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean connected() {
        try {
            if (!socket.isClosed() && socket.isBound() && socket.isConnected()) {
                return true; // good connection
            }
        } catch (final Exception e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Connection.connected error on connection from {} to {}",
                    selfId,
                    otherId,
                    e);
        }
        return false; // bad connection
    }

    @Override
    public void initForSync() throws IOException {
        if (!this.connected()) {
            throw new BadIOException("not a valid connection ");
        }

        /* track the number of bytes written and read during a sync */
        getDis().getSyncByteCounter().resetCount();
        getDos().getSyncByteCounter().resetCount();
        final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
        this.setTimeout(socketConfig.timeoutSyncClientSocket());
    }

    @Override
    public boolean isOutbound() {
        return outbound;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterRead() {
        try {
            if (quickAck) {
                // quick ack has to be set after each read - it just sends instant acknowledgement
                // it is NOT implemented on Mac as of 2025, but shouldn't hurt, except for some wasted cpu cycles
                getSocket().setOption(ExtendedSocketOptions.TCP_QUICKACK, true);
            }
        } catch (final IOException e) {
            // we can silently ignore it, as it is optional
        }
    }
}
