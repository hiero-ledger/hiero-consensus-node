// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connectivity;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.NetworkPeerIdentifier;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.SocketConfig;
import com.swirlds.platform.network.SocketConnection;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLSocket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.interrupt.InterruptableConsumer;
import org.hiero.consensus.model.node.NodeId;

/**
 * Accept inbound connections and executes the platform handshake. This class is thread-safe
 */
public class InboundConnectionHandler {
    private static final Logger logger = LogManager.getLogger(InboundConnectionHandler.class);
    private final ConnectionTracker connectionTracker;
    private final NodeId selfId;
    private final InterruptableConsumer<Connection> newConnectionConsumer;
    private final SocketConfig socketConfig;
    /** Rate Limited Logger for SocketExceptions */
    private final RateLimitedLogger socketExceptionLogger;

    private final PlatformContext platformContext;
    private final NetworkPeerIdentifier networkPeerIdentifier;
    private final Time time;

    /**
     * constructor
     *
     * @param platformContext       the platform context
     * @param connectionTracker     connection tracker for all platform connections
     * @param peers                 the list of peers
     * @param selfId                self's node id
     * @param newConnectionConsumer new connection consumer
     * @param time                  platform time
     */
    public InboundConnectionHandler(
            @NonNull final PlatformContext platformContext,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final List<PeerInfo> peers,
            @NonNull final NodeId selfId,
            @NonNull final InterruptableConsumer<Connection> newConnectionConsumer,
            @NonNull final Time time) {
        this.platformContext = Objects.requireNonNull(platformContext);
        this.connectionTracker = Objects.requireNonNull(connectionTracker);
        this.selfId = Objects.requireNonNull(selfId);
        this.newConnectionConsumer = Objects.requireNonNull(newConnectionConsumer);
        this.time = Objects.requireNonNull(time);
        this.socketExceptionLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
        this.socketConfig = platformContext.getConfiguration().getConfigData(SocketConfig.class);
        this.networkPeerIdentifier = new NetworkPeerIdentifier(platformContext, Objects.requireNonNull(peers));
    }

    /**
     * Identifies the peer that has just established a new connection and create a {@link Connection}
     *
     * @param clientSocket the newly created socket
     */
    public void handle(@NonNull final Socket clientSocket) {
        final long acceptTime = time.currentTimeMillis();
        Objects.requireNonNull(clientSocket);
        String remoteIp = "unknown";
        try {
            remoteIp = clientSocket.getInetAddress().toString();
            clientSocket.setTcpNoDelay(socketConfig.tcpNoDelay());
            clientSocket.setSoTimeout(socketConfig.timeoutSyncClientSocket());

            final SSLSocket sslSocket = (SSLSocket) clientSocket;
            final PeerInfo connectedPeer =
                    networkPeerIdentifier.identifyTlsPeer(sslSocket.getSession().getPeerCertificates());
            if (connectedPeer == null) {
                clientSocket.close();
                return;
            }
            final NodeId otherId = connectedPeer.nodeId();

            final SyncInputStream sis = SyncInputStream.createSyncInputStream(
                    platformContext, clientSocket.getInputStream(), socketConfig.bufferSize());
            final SyncOutputStream sos = SyncOutputStream.createSyncOutputStream(
                    platformContext, clientSocket.getOutputStream(), socketConfig.bufferSize());

            final SocketConnection sc = SocketConnection.create(
                    selfId,
                    otherId,
                    connectionTracker,
                    false,
                    clientSocket,
                    sis,
                    sos,
                    platformContext.getConfiguration());
            newConnectionConsumer.accept(sc);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            final String formattedException = NetworkUtils.formatException(e);
            logger.warn(
                    SOCKET_EXCEPTIONS.getMarker(),
                    "Inbound connection from {} to {} was interrupted: {}",
                    remoteIp,
                    selfId,
                    formattedException);
            NetworkUtils.close(clientSocket);
        } catch (final IOException e) {
            final String formattedException = NetworkUtils.formatException(e);
            socketExceptionLogger.warn(
                    SOCKET_EXCEPTIONS.getMarker(),
                    "Inbound connection from {} to {} had IOException: {}",
                    remoteIp,
                    selfId,
                    formattedException);
            NetworkUtils.close(clientSocket);
        } catch (final RuntimeException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Inbound connection error, remote IP: {}\n" + "Time from accept to exception: {} ms",
                    clientSocket.getInetAddress() != null
                            ? clientSocket.getInetAddress().toString()
                            : "null IP",
                    acceptTime == 0 ? "N/A" : (System.currentTimeMillis() - acceptTime),
                    e);
            NetworkUtils.close(clientSocket);
        }
    }

    /**
     * Creates a copy of handler with a set of new peers applied internally, everything else is copied directly
     *
     * @param newPeers list of new peers to accept
     * @return copy of curren handler with new set of peers
     */
    public InboundConnectionHandler withNewPeers(@NonNull final List<PeerInfo> newPeers) {
        return new InboundConnectionHandler(
                this.platformContext,
                this.connectionTracker,
                newPeers,
                this.selfId,
                this.newConnectionConsumer,
                this.time);
    }
}
