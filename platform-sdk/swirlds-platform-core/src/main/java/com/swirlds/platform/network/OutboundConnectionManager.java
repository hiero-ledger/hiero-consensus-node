// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.NETWORK;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.TCP_CONNECT_EXCEPTIONS;

import com.google.common.annotations.VisibleForTesting;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.config.GossipConfig;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.connection.NotConnectedConnection;
import com.swirlds.platform.network.connectivity.SocketFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.locks.AutoClosableResourceLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public class OutboundConnectionManager implements ConnectionManager {
    private final PlatformContext platformContext;
    private final NodeId selfId;
    private final ConnectionTracker connectionTracker;
    private final SocketConfig socketConfig;
    private final GossipConfig gossipConfig;
    private final PeerInfo otherPeer;
    /** the current connection in use, initially not connected. there is no synchronization on this variable */
    private Connection currentConn = NotConnectedConnection.getSingleton();
    /** locks the connection managed by this instance */
    private final AutoClosableResourceLock<Connection> lock = Locks.createResourceLock(currentConn);
    /**
     * this factory holds only required certificates and keys to do a single P2P connection between ourselves and single
     * other peer
     */
    private final SocketFactory socketFactory;

    private static final Logger logger = LogManager.getLogger(OutboundConnectionManager.class);

    /**
     * Creates new outbound connection manager
     *
     * @param selfId            self's node id
     * @param otherPeer         information about the peer we are supposed to connect to
     * @param platformContext   the platform context
     * @param connectionTracker connection tracker for all platform connections
     * @param ownKeysAndCerts   private keys and public certificates
     */
    public OutboundConnectionManager(
            @NonNull final NodeId selfId,
            @NonNull final PeerInfo otherPeer,
            @NonNull final PlatformContext platformContext,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final KeysAndCerts ownKeysAndCerts) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.selfId = Objects.requireNonNull(selfId);
        this.connectionTracker = Objects.requireNonNull(connectionTracker);
        this.otherPeer = otherPeer;
        this.socketConfig = platformContext.getConfiguration().getConfigData(SocketConfig.class);
        this.gossipConfig = platformContext.getConfiguration().getConfigData(GossipConfig.class);
        this.socketFactory = NetworkUtils.createSocketFactory(
                selfId, Collections.singletonList(otherPeer), ownKeysAndCerts, platformContext.getConfiguration());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Connection waitForConnection() {
        try (final LockedResource<Connection> resource = lock.lock()) {
            while (!resource.getResource().connected()) {
                resource.getResource().disconnect();
                final Connection connection = createConnection();
                resource.setResource(connection);
                if (!connection.connected() && this.socketConfig.waitBetweenConnectionRetries() > 0) {
                    try {
                        Thread.sleep(this.socketConfig.waitBetweenConnectionRetries());
                    } catch (InterruptedException e) {
                        return NotConnectedConnection.getSingleton();
                    }
                }
            }
            currentConn = resource.getResource();
        }
        return currentConn;
    }

    private Connection createConnection() {

        // NOTE: we always connect to the first ServiceEndpoint, which for now represents a legacy "external" address
        // (which may change in the future as new Rosters get installed).
        // There's no longer a distinction between "internal" and "external" endpoints in Roster,
        // and it would be complex and error-prone to build logic to guess which one is which.
        // Ideally, this code should use a randomized and/or round-robin approach to choose an appropriate endpoint.
        // For now, we default to the very first one at all times.
        final NetworkEndpoint networkEndpoint = gossipConfig
                .getEndpointOverride(otherPeer.nodeId().id())
                .orElseGet(() -> {
                    try {
                        return new NetworkEndpoint(
                                otherPeer.nodeId().id(), InetAddress.getByName(otherPeer.hostname()), otherPeer.port());
                    } catch (UnknownHostException e) {
                        throw new RuntimeException("Host '" + otherPeer.hostname() + "' not found", e);
                    }
                });

        Socket clientSocket = null;
        SyncOutputStream dos = null;
        SyncInputStream dis = null;

        try {
            clientSocket = socketFactory.createClientSocket(
                    networkEndpoint.hostname().getHostAddress(), networkEndpoint.port());

            dos = SyncOutputStream.createSyncOutputStream(
                    platformContext, clientSocket.getOutputStream(), socketConfig.bufferSize());
            dis = SyncInputStream.createSyncInputStream(
                    platformContext, clientSocket.getInputStream(), socketConfig.bufferSize());

            logger.debug(NETWORK.getMarker(), "`connect` : finished, {} connected to {}", selfId, otherPeer.nodeId());

            return SocketConnection.create(
                    selfId,
                    otherPeer.nodeId(),
                    connectionTracker,
                    true,
                    clientSocket,
                    dis,
                    dos,
                    platformContext.getConfiguration());
        } catch (final SocketTimeoutException | SocketException e) {
            NetworkUtils.close(clientSocket, dis, dos);
            logger.debug(
                    TCP_CONNECT_EXCEPTIONS.getMarker(),
                    "{} failed to connect to {} with error:",
                    selfId,
                    otherPeer.nodeId(),
                    e);
            // ConnectException (which is a subclass of SocketException) happens when calling someone
            // who isn't running yet. So don't worry about it.
            // Also ignore the other socket-related errors (SocketException) in case it times out while
            // connecting.
        } catch (final IOException e) {
            NetworkUtils.close(clientSocket, dis, dos);
            // log the SSL connection exception which is caused by socket exceptions as warning.
            final String formattedException = NetworkUtils.formatException(e);
            logger.warn(
                    SOCKET_EXCEPTIONS.getMarker(),
                    "{} failed to connect to {} {}",
                    selfId,
                    otherPeer.nodeId(),
                    formattedException);
        } catch (final RuntimeException e) {
            NetworkUtils.close(clientSocket, dis, dos);
            logger.debug(EXCEPTION.getMarker(), "{} failed to connect to {}", selfId, otherPeer.nodeId(), e);
        }

        return NotConnectedConnection.getSingleton();
    }

    /**
     * {@inheritDoc}
     */
    @VisibleForTesting
    @Override
    public Connection getConnection() {
        return currentConn;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void newConnection(final Connection connection) {
        throw new UnsupportedOperationException("Does not accept connections");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isOutbound() {
        return true;
    }
}
