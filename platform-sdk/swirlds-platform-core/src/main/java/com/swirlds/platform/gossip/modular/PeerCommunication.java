// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import com.google.common.collect.ImmutableList;
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
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Opening and monitoring of new connections for gossip/chatter neighbours.
 */
public class PeerCommunication implements ConnectionTracker {

    private static final Logger logger = LogManager.getLogger(PeerCommunication.class);
    public static final String PLATFORM_THREAD_POOL_NAME = "platform-core";

    private final Lock peerLock = new ReentrantLock();
    private final NetworkMetrics networkMetrics;
    private StaticTopology topology;
    private final KeysAndCerts keysAndCerts;
    private final PlatformContext platformContext;
    private ImmutableList<PeerInfo> peers;
    private final PeerInfo selfPeer;
    private StaticConnectionManagers connectionManagers;
    private NetworkPeerIdentifier peerIdentifier;
    private SocketFactory socketFactory;
    private ThreadManager threadManager;
    private NodeId selfId;
    private List<ProtocolRunnable> handshakeProtocols;
    private List<Protocol> protocolList;
    private OutboundConnectionCreator outboundConnectionCreator;

    /**
     * Create manager of communication with neighbouring nodes for exchanging events.
     *
     * @param platformContext the platform context
     * @param peers           the current list of peers
     * @param selfPeer        this node's data
     * @param keysAndCerts    private keys and public certificates
     */
    public PeerCommunication(
            @NonNull final PlatformContext platformContext,
            @NonNull final List<PeerInfo> peers,
            @NonNull final PeerInfo selfPeer,
            @NonNull final KeysAndCerts keysAndCerts) {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(peers);
        Objects.requireNonNull(selfPeer);
        Objects.requireNonNull(keysAndCerts);

        this.keysAndCerts = keysAndCerts;
        this.platformContext = platformContext;
        this.peers = ImmutableList.copyOf(peers);
        this.selfPeer = selfPeer;
        this.selfId = selfPeer.nodeId();

        this.networkMetrics = new NetworkMetrics(platformContext.getMetrics(), selfPeer.nodeId());
        platformContext.getMetrics().addUpdater(networkMetrics::update);

        this.topology = new StaticTopology(peers, selfPeer.nodeId());
    }

    /**
     * @return preconstructed topology to save doing same operations in multiple places
     */
    public StaticTopology getTopology() {
        return topology;
    }

    /**
     * @return network metrics to register data about communication traffic and latencies
     */
    public NetworkMetrics getNetworkMetrics() {
        return networkMetrics;
    }

    /**
     * Modify list of current connected peers. Notify all underlying components.
     * In case data for the same peer changes (one with same nodeId), it should be present in both removed and added lists,
     * with old data in removed and fresh data in added. Internally it will be first removed and then added, so there can be
     * a short moment when it will drop out of the network if disconnect happens at a bad moment.
     * @param added peers to be added
     * @param removed peers to be removed
     * @return set of per-peer thread information based on applied diff; it will contain nodeId->null for peers which got removed, nodeId->threadForFreshData for ones which got updated
     */
    public Collection<DedicatedStoppableThread> addRemovePeers(
            @NonNull final List<PeerInfo> added, @NonNull final List<PeerInfo> removed) {
        Objects.requireNonNull(added);
        Objects.requireNonNull(removed);

        List<DedicatedStoppableThread> threads = new ArrayList<>();

        if (!peerLock.tryLock()) {
            logger.error(
                    "Concurrent access attempted to addRemovePeers, it is a bad idea, as order won't be guaranteed");
            peerLock.lock();
        }
        try {
            Map<NodeId, PeerInfo> newPeers = new HashMap<>();
            for (PeerInfo peer : peers) {
                newPeers.put(peer.nodeId(), peer);
            }

            for (PeerInfo peerInfo : removed) {
                PeerInfo previousPeer = newPeers.remove(peerInfo.nodeId());
                if (previousPeer == null) {
                    logger.warn("Peer info for nodeId: {} not found for removal", peerInfo.nodeId());
                } else {

                    threads.add(new DedicatedStoppableThread(peerInfo.nodeId(), null));
                }
            }

            for (PeerInfo peerInfo : added) {
                PeerInfo oldData = newPeers.put(peerInfo.nodeId(), peerInfo);
                if (oldData != null) {
                    logger.warn(
                            "Peer info for nodeId: {} replaced without removal, new data {}, old data {}",
                            peerInfo.nodeId(),
                            peerInfo,
                            oldData);
                }
            }

            // maybe sort peers before converting to list to preserve similar order for various interations/prinouts?
            this.peers = ImmutableList.copyOf(newPeers.values());
            this.topology = new StaticTopology(peers, selfPeer.nodeId());

            connectionManagers.addRemovePeers(added, removed, topology);
            peerIdentifier.addRemovePeers(added, removed);
            outboundConnectionCreator.addRemovePeers(added, removed);
            socketFactory.reload(peers);

            threads.addAll(
                    buildProtocolThreads(added.stream().map(PeerInfo::nodeId).toList()));
        } finally {
            peerLock.unlock();
        }

        return threads;
    }

    /**
     * Internal method similar to {@link #addRemovePeers(List, List)}, to be used during initialization for core set of peers
     * @return see {@link #addRemovePeers(List, List)}
     */
    List<DedicatedStoppableThread> buildProtocolThreadsFromCurrentNeighbors() {
        return buildProtocolThreads(topology.getNeighbors());
    }

    private List<DedicatedStoppableThread> buildProtocolThreads(Collection<NodeId> peers) {

        var syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final BasicConfig basicConfig = platformContext.getConfiguration().getConfigData(BasicConfig.class);
        final Duration hangingThreadDuration = basicConfig.hangingThreadDuration();
        var syncProtocolThreads = new ArrayList<DedicatedStoppableThread>();
        for (final NodeId otherId : peers) {
            syncProtocolThreads.add(new DedicatedStoppableThread(
                    otherId,
                    new StoppableThreadConfiguration<>(threadManager)
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
                            .build()));
        }
        return syncProtocolThreads;
    }

    /**
     * Second half of constructor, to initialize things which cannot be passed in the constructor for whatever reasons
     * @param threadManager                 the thread manager
     * @param handshakeProtocols            list of handshake protocols for new connections
     * @param protocols                     list of peer protocols for handling data for established connection
     * @return list of utility thread to be started together with a start of platform
     */
    List<StoppableThread> initialize(
            ThreadManager threadManager, List<ProtocolRunnable> handshakeProtocols, List<Protocol> protocols) {

        this.threadManager = threadManager;
        this.handshakeProtocols = handshakeProtocols;
        this.protocolList = protocols;

        var threadsToRun = new ArrayList<StoppableThread>();

        final ThreadConfig threadConfig = platformContext.getConfiguration().getConfigData(ThreadConfig.class);

        this.peerIdentifier = new NetworkPeerIdentifier(platformContext, peers);
        this.socketFactory =
                NetworkUtils.createSocketFactory(selfId, peers, keysAndCerts, platformContext.getConfiguration());
        // create an instance that can create new outbound connections
        this.outboundConnectionCreator =
                new OutboundConnectionCreator(platformContext, selfId, this, socketFactory, peers);
        this.connectionManagers = new StaticConnectionManagers(topology, outboundConnectionCreator);
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
        threadsToRun.add(new StoppableThreadConfiguration<>(threadManager)
                .setPriority(threadConfig.threadPrioritySync())
                .setNodeId(selfId)
                .setComponent(PLATFORM_THREAD_POOL_NAME)
                .setThreadName("connectionServer")
                .setWork(connectionServer)
                .build());

        return threadsToRun;
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
