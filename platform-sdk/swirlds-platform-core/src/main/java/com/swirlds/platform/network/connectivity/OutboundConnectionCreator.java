// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connectivity;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.NETWORK;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.TCP_CONNECT_EXCEPTIONS;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.config.GossipConfig;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.SocketConfig;
import com.swirlds.platform.network.SocketConnection;
import com.swirlds.platform.network.connection.NotConnectedConnection;
import com.swirlds.platform.roster.RosterEntryNotFoundException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Creates outbound connections to the requested peers
 */
public class OutboundConnectionCreator {
    private static final Logger logger = LogManager.getLogger(OutboundConnectionCreator.class);
    private static final String LOCALHOST = "127.0.0.1";
    private final NodeId selfId;
    private final SocketConfig socketConfig;
    private final GossipConfig gossipConfig;
    private final ConnectionTracker connectionTracker;
    private final SocketFactory socketFactory;
    private final PlatformContext platformContext;
    private final Map<NodeId, PeerInfo> peers = new HashMap<>();

    public OutboundConnectionCreator(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final SocketFactory socketFactory,
            @NonNull final List<PeerInfo> peers) {
        this.platformContext = Objects.requireNonNull(platformContext);
        this.selfId = Objects.requireNonNull(selfId);
        this.connectionTracker = Objects.requireNonNull(connectionTracker);
        this.socketFactory = Objects.requireNonNull(socketFactory);
        this.peers.putAll(peers.stream().collect(Collectors.toMap(PeerInfo::nodeId, Function.identity())));
        this.socketConfig = platformContext.getConfiguration().getConfigData(SocketConfig.class);
        this.gossipConfig = platformContext.getConfiguration().getConfigData(GossipConfig.class);
    }

    /**
     * Try to connect to the member with the given ID. If it doesn't work on the first try, give up immediately. Return
     * the connection, or a connection that is not connected if it fails.
     *
     * @param otherId which member to connect to
     * @return the new connection, or a connection that is not connected if it couldn't connect on the first try
     */
    public Connection createConnection(final NodeId otherId) {

        final PeerInfo other = peers.get(otherId);
        if (other == null) {
            throw new RosterEntryNotFoundException(
                    "No RosterEntry with nodeId: " + otherId + " in peer list: " + peers.keySet());
        }

        // NOTE: we always connect to the first ServiceEndpoint, which for now represents a legacy "external" address
        // (which may change in the future as new Rosters get installed).
        // There's no longer a distinction between "internal" and "external" endpoints in Roster,
        // and it would be complex and error-prone to build logic to guess which one is which.
        // Ideally, this code should use a randomized and/or round-robin approach to choose an appropriate endpoint.
        // For now, we default to the very first one at all times.
        final NetworkEndpoint networkEndpoint = gossipConfig
                .getEndpointOverride(otherId.id())
                .orElseGet(() -> {
                    try {
                        return new NetworkEndpoint(otherId.id(), InetAddress.getByName(other.hostname()), other.port());
                    } catch (UnknownHostException e) {
                        throw new RuntimeException("Host '" + other.hostname() + "' not found", e);
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

            logger.debug(NETWORK.getMarker(), "`connect` : finished, {} connected to {}", selfId, otherId);

            return SocketConnection.create(
                    selfId,
                    otherId,
                    connectionTracker,
                    true,
                    clientSocket,
                    dis,
                    dos,
                    platformContext.getConfiguration());
        } catch (final SocketTimeoutException | SocketException e) {
            NetworkUtils.close(clientSocket, dis, dos);
            logger.debug(
                    TCP_CONNECT_EXCEPTIONS.getMarker(), "{} failed to connect to {} with error:", selfId, otherId, e);
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
                    otherId,
                    formattedException);
        } catch (final RuntimeException e) {
            NetworkUtils.close(clientSocket, dis, dos);
            logger.debug(EXCEPTION.getMarker(), "{} failed to connect to {}", selfId, otherId, e);
        }

        return NotConnectedConnection.getSingleton();
    }
}
