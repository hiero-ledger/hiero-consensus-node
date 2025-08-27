// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.google.common.collect.ImmutableList;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.TypedStoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.ThreadConfig;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.network.communication.NegotiationProtocols;
import com.swirlds.platform.network.communication.ProtocolNegotiatorThread;
import com.swirlds.platform.network.connectivity.InboundConnectionHandler;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.network.topology.ConnectionManagerFactory;
import com.swirlds.platform.network.topology.DynamicConnectionManagers;
import com.swirlds.platform.network.topology.StaticTopology;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * Opening and monitoring of new connections for gossip/broadcast neighbours.
 */
public class PeerCommunication implements ConnectionTracker {

    private static final Logger logger = LogManager.getLogger(PeerCommunication.class);
    public static final String PLATFORM_THREAD_POOL_NAME = "platform-core";

    private final AutoClosableLock peerLock = Locks.createAutoLock();
    private final NetworkMetrics networkMetrics;
    private StaticTopology topology;
    private final KeysAndCerts ownKeysAndCerts;
    private final PlatformContext platformContext;
    private ImmutableList<PeerInfo> peers;
    private final PeerInfo selfPeer;
    private DynamicConnectionManagers connectionManagers;
    private ThreadManager threadManager;
    private final NodeId selfId;
    private List<ProtocolRunnable> handshakeProtocols;
    private List<Protocol> protocolList;
    private PeerConnectionServer connectionServer;

    private final Map<Object, DedicatedStoppableThread<NodeId>> dedicatedThreads = new HashMap<>();
    private final List<DedicatedStoppableThread<NodeId>> dedicatedThreadsToModify = new ArrayList<>();
    private boolean started = false;
    private TypedStoppableThread<InterruptableRunnable> connectionServerThread;

    /**
     * Create manager of communication with neighbouring nodes for exchanging events.
     *
     * @param platformContext the platform context
     * @param peers           the current list of peers
     * @param selfPeer        this node's data
     * @param ownKeysAndCerts private keys and public certificates for this node
     */
    public PeerCommunication(
            @NonNull final PlatformContext platformContext,
            @NonNull final List<PeerInfo> peers,
            @NonNull final PeerInfo selfPeer,
            @NonNull final KeysAndCerts ownKeysAndCerts) {

        this.ownKeysAndCerts = Objects.requireNonNull(ownKeysAndCerts);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.peers = ImmutableList.copyOf(Objects.requireNonNull(peers));
        this.selfPeer = Objects.requireNonNull(selfPeer);
        this.selfId = selfPeer.nodeId();

        this.networkMetrics = new NetworkMetrics(platformContext.getMetrics(), selfPeer.nodeId());
        platformContext.getMetrics().addUpdater(networkMetrics::update);

        this.topology = new StaticTopology(peers, selfPeer.nodeId());
    }

    /**
     * Second half of constructor, to initialize things which cannot be passed in the constructor for whatever reasons
     *
     * @param threadManager      the thread manager
     * @param handshakeProtocols list of handshake protocols for new connections
     * @param protocols          list of peer protocols for handling data for established connection
     */
    public void initialize(
            @NonNull final ThreadManager threadManager,
            @NonNull final List<ProtocolRunnable> handshakeProtocols,
            @NonNull final List<Protocol> protocols) {

        this.threadManager = threadManager;
        this.handshakeProtocols = handshakeProtocols;
        this.protocolList = protocols;

        this.connectionManagers = new DynamicConnectionManagers(
                selfId, peers, platformContext, this, ownKeysAndCerts, topology, ConnectionManagerFactory.DEFAULT);

        this.connectionServer = createConnectionServer();

        final ThreadConfig threadConfig = platformContext.getConfiguration().getConfigData(ThreadConfig.class);

        this.connectionServerThread = new StoppableThreadConfiguration<>(threadManager)
                .setPriority(threadConfig.threadPrioritySync())
                .setNodeId(selfId)
                .setComponent(PLATFORM_THREAD_POOL_NAME)
                .setThreadName("connectionServer")
                .setWork(connectionServer)
                .build();

        registerDedicatedThreads(buildProtocolThreads(topology.getNeighbors()));
    }

    /**
     * @return network metrics to register data about communication traffic and latencies
     */
    public NetworkMetrics getNetworkMetrics() {
        return networkMetrics;
    }

    /**
     * Modify list of current connected peers. Notify all underlying components. In case data for the same peer changes
     * (one with same nodeId), it should be present in both removed and added lists, with old data in removed and fresh
     * data in added. Internally it will be first removed and then added, so there can be a short moment when it will
     * drop out of the network if disconnect happens at a bad moment.
     *
     * @param added   peers to be added
     * @param removed peers to be removed
     */
    public void addRemovePeers(@NonNull final List<PeerInfo> added, @NonNull final List<PeerInfo> removed) {
        Objects.requireNonNull(added);
        Objects.requireNonNull(removed);

        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }

        List<DedicatedStoppableThread<NodeId>> threads = new ArrayList<>();

        try (final var ignored = peerLock.lock()) {
            Map<NodeId, PeerInfo> newPeers = new HashMap<>();
            for (PeerInfo peer : peers) {
                newPeers.put(peer.nodeId(), peer);
            }

            for (PeerInfo peerInfo : removed) {
                PeerInfo previousPeer = newPeers.remove(peerInfo.nodeId());
                if (previousPeer == null) {
                    logger.warn("Peer info for nodeId: {} not found for removal", peerInfo.nodeId());
                } else {

                    threads.add(new DedicatedStoppableThread<NodeId>(peerInfo.nodeId(), null));
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
            connectionServer.replacePeers(peers);

            threads.addAll(
                    buildProtocolThreads(added.stream().map(PeerInfo::nodeId).toList()));

            // having it inside the locked block is not really helping much, if addRemovePeers is called
            // concurrently, things WILL get messed up, but at least we won't fail with concurrent modification exc
            registerDedicatedThreads(threads);
            applyDedicatedThreadsToModify();
        }
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

    /**
     * Spin up all the threads registered for already existing peers
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("Gossip already started");
        }
        started = true;

        this.connectionServerThread.start();

        applyDedicatedThreadsToModify();
    }

    /**
     * Stop all network threads
     */
    public void stop() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }

        this.connectionServerThread.stop();

        for (final DedicatedStoppableThread dst : dedicatedThreads.values()) {
            dst.thread().interrupt(); // aggresive interrupt to avoid hanging for a long time
            dst.thread().stop();
        }
    }

    private List<DedicatedStoppableThread<NodeId>> buildProtocolThreads(Collection<NodeId> peers) {

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final BasicConfig basicConfig = platformContext.getConfiguration().getConfigData(BasicConfig.class);
        final Duration hangingThreadDuration = basicConfig.hangingThreadDuration();
        final ArrayList<DedicatedStoppableThread<NodeId>> syncProtocolThreads =
                new ArrayList<DedicatedStoppableThread<NodeId>>();
        for (final NodeId otherId : peers) {
            syncProtocolThreads.add(new DedicatedStoppableThread<NodeId>(
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

    private PeerConnectionServer createConnectionServer() {
        var inboundConnectionHandler = new InboundConnectionHandler(
                platformContext, this, peers, selfId, connectionManagers::newConnection, platformContext.getTime());
        // allow other members to create connections to me
        // Assume all ServiceEndpoints use the same port and use the port from the first endpoint.
        // Previously, this code used a "local port" corresponding to the internal endpoint,
        // which should normally be the second entry in the endpoints list if it's obtained via
        // a regular AddressBook -> Roster conversion.
        // The assumption must be correct, otherwise, if ports were indeed different, then the old code
        // using the AddressBook would never have listened on a port associated with the external endpoint,
        // thus not allowing anyone to connect to the node from outside the local network, which we'd have noticed.
        var socketFactory =
                NetworkUtils.createSocketFactory(selfId, peers, ownKeysAndCerts, platformContext.getConfiguration());
        return new PeerConnectionServer(
                threadManager,
                selfPeer.port(),
                inboundConnectionHandler,
                socketFactory,
                platformContext
                        .getConfiguration()
                        .getConfigData(SocketConfig.class)
                        .maxSocketAcceptThreads());
    }

    /**
     * Registers threads which should be started when {@link #start()} method is called and stopped on {@link #stop()}
     * Order in which this method is called is important, so don't call it concurrently without external control.
     *
     * @param things thread to start
     */
    private void registerDedicatedThreads(final @NonNull Collection<DedicatedStoppableThread<NodeId>> things) {
        Objects.requireNonNull(things);
        dedicatedThreadsToModify.addAll(things);
    }

    /**
     * Should be called after {@link #registerDedicatedThreads(Collection)} to actually start/stop threads; it is split
     * into half because this method can be called only for running system, so during startup, dedicated threads will be
     * registered a lot earlier than started Method can be called many times, it will be no-op if no dedicate thread
     * changes were made in meantime Do NOT call this method concurrently; it is not protected against such access and
     * can have undefined behaviour
     */
    private void applyDedicatedThreadsToModify() {
        if (!started) {
            logger.warn("Cannot apply dedicated threads status when gossip is not started");
            return;
        }
        for (DedicatedStoppableThread<NodeId> dst : dedicatedThreadsToModify) {
            var newThread = dst.thread();
            var oldThread = dedicatedThreads.remove(dst.key());
            if (newThread == null) {
                if (oldThread != null && oldThread.thread() != null) {
                    // we are doing interrupt here (and below) instead of stop(), because stop() can block forever
                    // in case of non-cooperating thread blocking in strange place; as future extension, stop()
                    // behaviour can be extended to do best effort stop only, to avoid locking thread
                    oldThread.thread().interrupt();
                } else {
                    logger.warn("Dedicated thread {} was not found, but we were asked to stop it", dst.key());
                }
            } else {
                if (oldThread != null && oldThread.thread() != null) {
                    oldThread.thread().interrupt();
                }
                dedicatedThreads.put(dst.key(), dst);
                newThread.start();
            }
        }
        dedicatedThreadsToModify.clear();
    }
}

/**
 * Represents a thread created for a specific context
 *
 * @param key    opaque context for which this thread is created
 * @param thread thread itself, to be started/stopped/forgotten depending on the key context
 */
record DedicatedStoppableThread<E>(@NonNull E key, @Nullable StoppableThread thread) {}
