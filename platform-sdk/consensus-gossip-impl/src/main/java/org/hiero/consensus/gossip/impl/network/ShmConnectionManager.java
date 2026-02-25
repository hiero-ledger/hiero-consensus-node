package org.hiero.consensus.gossip.impl.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.locks.AutoClosableResourceLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.network.connection.NotConnectedConnection;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public class ShmConnectionManager implements ConnectionManager {

    private final Configuration configuration;
    private final NodeId selfId;
    private final ConnectionTracker connectionTracker;
    private final SocketConfig socketConfig;
    private final GossipConfig gossipConfig;
    private final PeerInfo otherPeer;
    /** the current connection in use, initially not connected. there is no synchronization on this variable */
    private Connection currentConn = NotConnectedConnection.getSingleton();
    /** locks the connection managed by this instance */
    private final AutoClosableResourceLock<Connection> lock = Locks.createResourceLock(currentConn);

    private static final Logger logger = LogManager.getLogger(OutboundConnectionManager.class);

    /**
     * Creates new outbound connection manager
     *
     * @param configuration     platform configuration
     * @param time              source of time
     * @param selfId            self's node id
     * @param otherPeer         information about the peer we are supposed to connect to
     * @param connectionTracker connection tracker for all platform connections
     * @param ownKeysAndCerts   private keys and public certificates
     */
    public ShmConnectionManager(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final PeerInfo otherPeer,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final KeysAndCerts ownKeysAndCerts) {

        this.configuration = Objects.requireNonNull(configuration);
        this.selfId = Objects.requireNonNull(selfId);
        this.connectionTracker = Objects.requireNonNull(connectionTracker);
        this.otherPeer = otherPeer;
        this.socketConfig = configuration.getConfigData(SocketConfig.class);
        this.gossipConfig = configuration.getConfigData(GossipConfig.class);
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

        try {

            return ShmConnection.create(selfId, otherPeer.nodeId(), connectionTracker, configuration);
        } catch (final Exception e) {
            // log the SSL connection exception which is caused by socket exceptions as warning.
            final String formattedException = NetworkUtils.formatException(e);
            logger.warn(
                    SOCKET_EXCEPTIONS.getMarker(),
                    "{} failed to connect to {} {}",
                    selfId,
                    otherPeer.nodeId(),
                    formattedException);
        }

        return NotConnectedConnection.getSingleton();
    }

    /**
     * {@inheritDoc}
     */
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
