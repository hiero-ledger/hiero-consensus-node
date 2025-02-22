// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.ThreadConfig;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.network.*;
import com.swirlds.platform.network.communication.NegotiationProtocols;
import com.swirlds.platform.network.communication.ProtocolNegotiatorThread;
import com.swirlds.platform.network.connectivity.ConnectionServer;
import com.swirlds.platform.network.connectivity.InboundConnectionHandler;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import com.swirlds.platform.network.connectivity.SocketFactory;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.network.topology.StaticConnectionManagers;
import com.swirlds.platform.network.topology.StaticTopology;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Opening and monitoring of new connections for gossip/chatter neighbours.
 */
public class PeerCommunication implements ConnectionTracker {

    private static final Logger logger = LogManager.getLogger(PeerCommunication.class);
    public static final String PLATFORM_THREAD_POOL_NAME = "platform-core";

    private final NetworkMetrics networkMetrics;
    private final StaticTopology topology;
    private final KeysAndCerts keysAndCerts;
    private final PlatformContext platformContext;
    private final List<PeerInfo> peers;
    private final PeerInfo selfPeer;

    /**
     * Create manager of communication with neighbouring nodes for exchanging events.
     *
     * @param platformContext               the platform context
     * @param peers                        the current list of peers
     * @param selfPeer                        this node's data
     * @param keysAndCerts                  private keys and public certificates
     */
    public PeerCommunication(
            @NonNull final PlatformContext platformContext,
            @NonNull final List<PeerInfo> peers,
            @NonNull final PeerInfo selfPeer,
            @NonNull final KeysAndCerts keysAndCerts) {

        this.keysAndCerts = keysAndCerts;
        this.platformContext = platformContext;
        this.peers = peers;
        this.selfPeer = selfPeer;

        this.networkMetrics = new NetworkMetrics(platformContext.getMetrics(), selfPeer.nodeId(), this.peers);
        platformContext.getMetrics().addUpdater(networkMetrics::update);

        this.topology = new StaticTopology(peers, selfPeer.nodeId());
    }

    /**
     *
     * @return preconstructed topology to save doing same operations in multiple places
     */
    public StaticTopology getTopology() {
        return topology;
    }

    /**
     *
     * @return network metrics to register data about communication traffic and latencies
     */
    public NetworkMetrics getNetworkMetrics() {
        return networkMetrics;
    }

    /**
     *
     * @return list of peers for current static topology
     */
    public List<PeerInfo> getPeers() {
        return peers;
    }

    List<StoppableThread> buildProtocolThreads(
            final ThreadManager threadManager,
            final NodeId selfId,
            final List<ProtocolRunnable> handshakeProtocols,
            final List<Protocol> protocolList) {

        var syncProtocolThreads = new ArrayList<StoppableThread>();

        final BasicConfig basicConfig = platformContext.getConfiguration().getConfigData(BasicConfig.class);
        final Duration hangingThreadDuration = basicConfig.hangingThreadDuration();

        final ThreadConfig threadConfig = platformContext.getConfiguration().getConfigData(ThreadConfig.class);

        final NetworkPeerIdentifier peerIdentifier = new NetworkPeerIdentifier(platformContext, peers);
        final SocketFactory socketFactory =
                NetworkUtils.createSocketFactory(selfId, peers, keysAndCerts, platformContext.getConfiguration());
        // create an instance that can create new outbound connections
        final OutboundConnectionCreator connectionCreator =
                new OutboundConnectionCreator(platformContext, selfId, this, socketFactory, peers);
        var connectionManagers = new StaticConnectionManagers(topology, connectionCreator);
        final InboundConnectionHandler inboundConnectionHandler = new InboundConnectionHandler(
                platformContext,
                this,
                peerIdentifier,
                selfId,
                connectionManagers::newConnection,
                platformContext.getTime());
        // allow other members to create connections to me
        // Assume all ServiceEndpoints use the same port and use the port from the first endpoint.
        // Previously, this code used a "local port" corresponding to the internal endpoint,
        // which should normally be the second entry in the endpoints list if it's obtained via
        // a regular AddressBook -> Roster conversion.
        // The assumption must be correct, otherwise, if ports were indeed different, then the old code
        // using the AddressBook would never have listened on a port associated with the external endpoint,
        // thus not allowing anyone to connect to the node from outside the local network, which we'd have noticed.
        final ConnectionServer connectionServer =
                new ConnectionServer(threadManager, selfPeer.port(), socketFactory, inboundConnectionHandler::handle);
        syncProtocolThreads.add(new StoppableThreadConfiguration<>(threadManager)
                .setPriority(threadConfig.threadPrioritySync())
                .setNodeId(selfId)
                .setComponent(PLATFORM_THREAD_POOL_NAME)
                .setThreadName("connectionServer")
                .setWork(connectionServer)
                .build());

        var syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        for (final NodeId otherId : topology.getNeighbors()) {
            syncProtocolThreads.add(new StoppableThreadConfiguration<>(threadManager)
                    .setPriority(Thread.NORM_PRIORITY)
                    .setNodeId(selfId)
                    .setComponent(PLATFORM_THREAD_POOL_NAME)
                    .setOtherNodeId(otherId)
                    .setThreadName("SyncProtocolWith" + otherId)
                    .setHangingThreadPeriod(hangingThreadDuration)
                    .setWork(new ProtocolNegotiatorThread(
                            connectionManagers.getManager(otherId),
                            syncConfig.syncSleepAfterFailedNegotiation(),
                            handshakeProtocols,
                            new NegotiationProtocols(protocolList.stream()
                                    .map(protocol -> protocol.createPeerInstance(otherId))
                                    .toList()),
                            platformContext.getTime()))
                    .build());
        }
        return syncProtocolThreads;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void newConnectionOpened(@NonNull final Connection sc) {
        Objects.requireNonNull(sc);
        networkMetrics.connectionEstablished(sc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void connectionClosed(final boolean outbound, @NonNull final Connection conn) {
        Objects.requireNonNull(conn);
        networkMetrics.recordDisconnect(conn);
    }
}
