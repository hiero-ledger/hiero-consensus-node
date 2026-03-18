// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.connectivity;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLSocket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.interrupt.InterruptableConsumer;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.ConnectionTracker;
import org.hiero.consensus.gossip.impl.network.NetworkPeerIdentifier;
import org.hiero.consensus.gossip.impl.network.NetworkUtils;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.gossip.impl.network.SocketConnection;
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

    private final Configuration configuration;
    private final NetworkPeerIdentifier networkPeerIdentifier;
    private final Time time;

    /**
     * constructor
     *
     * @param configuration the configuration
     * @param time the source of time
     * @param connectionTracker connection tracker for all platform connections
     * @param peers the list of peers
     * @param selfId self's node id
     * @param newConnectionConsumer new connection consumer
     */
    public InboundConnectionHandler(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final List<PeerInfo> peers,
            @NonNull final NodeId selfId,
            @NonNull final InterruptableConsumer<Connection> newConnectionConsumer) {
        this.configuration = requireNonNull(configuration);
        this.connectionTracker = requireNonNull(connectionTracker);
        this.selfId = requireNonNull(selfId);
        this.newConnectionConsumer = requireNonNull(newConnectionConsumer);
        this.time = requireNonNull(time);
        this.socketExceptionLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
        this.socketConfig = configuration.getConfigData(SocketConfig.class);
        this.networkPeerIdentifier = new NetworkPeerIdentifier(time, requireNonNull(peers));
    }

    /**
     * Identifies the peer that has just established a new connection and create a {@link Connection}
     *
     * @param clientSocket the newly created socket
     */
    public void handle(@NonNull final Socket clientSocket) {
        final long acceptTime = time.currentTimeMillis();
        requireNonNull(clientSocket);
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
                    configuration, clientSocket.getInputStream(), socketConfig.bufferSize());
            final SyncOutputStream sos = SyncOutputStream.createSyncOutputStream(
                    configuration, clientSocket.getOutputStream(), socketConfig.bufferSize());

            final SocketConnection sc = SocketConnection.create(
                    selfId, otherId, connectionTracker, false, clientSocket, sis, sos, configuration);
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
                this.configuration,
                this.time,
                this.connectionTracker,
                newPeers,
                this.selfId,
                this.newConnectionConsumer);
    }
}
