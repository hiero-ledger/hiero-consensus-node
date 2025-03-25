// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_UNDEFINED;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.state.Startable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.config.ThreadConfig;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.communication.NegotiationProtocols;
import com.swirlds.platform.network.communication.ProtocolNegotiatorThread;
import com.swirlds.platform.network.communication.handshake.VersionCompareHandshake;
import com.swirlds.platform.network.connectivity.ConnectionServer;
import com.swirlds.platform.network.connectivity.InboundConnectionHandler;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import com.swirlds.platform.network.connectivity.SocketFactory;
import com.swirlds.platform.network.protocol.HeartbeatProtocol;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.network.protocol.ReconnectProtocol;
import com.swirlds.platform.network.protocol.SyncProtocol;
import com.swirlds.platform.network.topology.NetworkTopology;
import com.swirlds.platform.network.topology.StaticConnectionManagers;
import com.swirlds.platform.network.topology.StaticTopology;
import com.swirlds.platform.reconnect.DefaultSignedStateValidator;
import com.swirlds.platform.reconnect.ReconnectController;
import com.swirlds.platform.reconnect.ReconnectHelper;
import com.swirlds.platform.reconnect.ReconnectLearnerFactory;
import com.swirlds.platform.reconnect.ReconnectLearnerThrottle;
import com.swirlds.platform.reconnect.ReconnectThrottle;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.wiring.NoInput;
import com.swirlds.platform.wiring.components.Gossip;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Boilerplate code for gossip.
 */
public class SyncGossip implements ConnectionTracker, Gossip {
    public static final String PLATFORM_THREAD_POOL_NAME = "platform-core";
    private static final Logger logger = LogManager.getLogger(SyncGossip.class);

    private boolean started = false;

    private final ReconnectController reconnectController;

    private final AtomicBoolean gossipHalted = new AtomicBoolean(false);
    private final SyncPermitProvider syncPermitProvider;
    private final SyncConfig syncConfig;
    private final Shadowgraph shadowgraph;
    private final ShadowgraphSynchronizer syncShadowgraphSynchronizer;

    /**
     * Keeps track of the number of events in the intake pipeline from each peer
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * A list of threads that execute the sync protocol using bidirectional connections
     */
    private final List<StoppableThread> syncProtocolThreads = new ArrayList<>();

    private final NetworkTopology topology;
    private final NetworkMetrics networkMetrics;
    private final ReconnectHelper reconnectHelper;
    private final StaticConnectionManagers connectionManagers;
    private final FallenBehindManagerImpl fallenBehindManager;
    private final SyncManagerImpl syncManager;
    private final ReconnectThrottle reconnectThrottle;
    private final ReconnectMetrics reconnectMetrics;

    protected final StatusActionSubmitter statusActionSubmitter;
    protected final AtomicReference<PlatformStatus> currentPlatformStatus =
            new AtomicReference<>(PlatformStatus.STARTING_UP);

    private final List<Startable> thingsToStart = new ArrayList<>();

    private Consumer<PlatformEvent> receivedEventHandler;

    /**
     * Builds the gossip engine, depending on which flavor is requested in the configuration.
     *
     * @param platformContext               the platform context
     * @param threadManager                 the thread manager
     * @param keysAndCerts                  private keys and public certificates
     * @param roster                        the current roster
     * @param selfId                        this node's ID
     * @param appVersion                    the version of the app
     * @param swirldStateManager            manages the mutable state
     * @param latestCompleteState           holds the latest signed state that has enough signatures to be verifiable
     * @param statusActionSubmitter         for submitting updates to the platform status manager
     * @param loadReconnectState            a method that should be called when a state from reconnect is obtained
     * @param clearAllPipelinesForReconnect this method should be called to clear all pipelines prior to a reconnect
     * @param intakeEventCounter            keeps track of the number of events in the intake pipeline from each peer
     * @param platformStateFacade           a facade for accessing the platform state
     */
    public SyncGossip(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final SoftwareVersion appVersion,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final Consumer<SignedState> loadReconnectState,
            @NonNull final Runnable clearAllPipelinesForReconnect,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final PlatformStateFacade platformStateFacade) {

        shadowgraph = new Shadowgraph(platformContext, roster.rosterEntries().size(), intakeEventCounter);

        this.statusActionSubmitter = Objects.requireNonNull(statusActionSubmitter);

        final ThreadConfig threadConfig = platformContext.getConfiguration().getConfigData(ThreadConfig.class);

        final BasicConfig basicConfig = platformContext.getConfiguration().getConfigData(BasicConfig.class);
        final RosterEntry selfEntry = RosterUtils.getRosterEntry(roster, selfId.id());
        final List<PeerInfo> peers;
        final X509Certificate selfCert = RosterUtils.fetchGossipCaCertificate(selfEntry);
        if (!CryptoStatic.checkCertificate(selfCert)) {
            // Do not make peer connections if the self node does not have a valid signing certificate in the roster.
            // https://github.com/hashgraph/hedera-services/issues/16648
            logger.error(
                    EXCEPTION.getMarker(),
                    "The gossip certificate for node {} is missing or invalid. "
                            + "This node will not connect to any peers.",
                    selfId);
            peers = Collections.emptyList();
        } else {
            peers = Utilities.createPeerInfoList(roster, selfId);
        }

        topology = new StaticTopology(peers, selfId);
        final SocketFactory socketFactory =
                NetworkUtils.createSocketFactory(selfId, peers, keysAndCerts, platformContext.getConfiguration());
        // create an instance that can create new outbound connections
        final OutboundConnectionCreator connectionCreator =
                new OutboundConnectionCreator(platformContext, selfId, this, socketFactory, peers);
        connectionManagers = new StaticConnectionManagers(topology, connectionCreator);
        final InboundConnectionHandler inboundConnectionHandler = new InboundConnectionHandler(
                platformContext, this, peers, selfId, connectionManagers::newConnection, platformContext.getTime());
        // allow other members to create connections to me
        final RosterEntry rosterEntry = RosterUtils.getRosterEntry(roster, selfId.id());
        // Assume all ServiceEndpoints use the same port and use the port from the first endpoint.
        // Previously, this code used a "local port" corresponding to the internal endpoint,
        // which should normally be the second entry in the endpoints list if it's obtained via
        // a regular AddressBook -> Roster conversion.
        // The assumption must be correct, otherwise, if ports were indeed different, then the old code
        // using the AddressBook would never have listened on a port associated with the external endpoint,
        // thus not allowing anyone to connect to the node from outside the local network, which we'd have noticed.
        final ConnectionServer connectionServer = new ConnectionServer(
                threadManager, RosterUtils.fetchPort(rosterEntry, 0), socketFactory, inboundConnectionHandler::handle);
        thingsToStart.add(new StoppableThreadConfiguration<>(threadManager)
                .setPriority(threadConfig.threadPrioritySync())
                .setNodeId(selfId)
                .setComponent(PLATFORM_THREAD_POOL_NAME)
                .setThreadName("connectionServer")
                .setWork(connectionServer)
                .build());

        fallenBehindManager = new FallenBehindManagerImpl(
                selfId,
                peers.size(),
                statusActionSubmitter,
                () -> getReconnectController().start(),
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class));

        syncManager = new SyncManagerImpl(platformContext, fallenBehindManager);

        final ReconnectConfig reconnectConfig =
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class);

        reconnectThrottle = new ReconnectThrottle(reconnectConfig, platformContext.getTime());

        networkMetrics = new NetworkMetrics(platformContext.getMetrics(), selfId);
        platformContext.getMetrics().addUpdater(networkMetrics::update);

        reconnectMetrics = new ReconnectMetrics(platformContext.getMetrics());

        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);

        final LongSupplier getRoundSupplier = () -> {
            try (final ReservedSignedState reservedState = latestCompleteState.get()) {
                if (reservedState == null || reservedState.isNull()) {
                    return ROUND_UNDEFINED;
                }

                return reservedState.get().getRound();
            }
        };

        reconnectHelper = new ReconnectHelper(
                this::pause,
                clearAllPipelinesForReconnect::run,
                swirldStateManager::getConsensusState,
                getRoundSupplier,
                new ReconnectLearnerThrottle(platformContext.getTime(), selfId, reconnectConfig),
                state -> {
                    loadReconnectState.accept(state);
                    syncManager.resetFallenBehind();
                },
                new ReconnectLearnerFactory(
                        platformContext,
                        threadManager,
                        roster,
                        reconnectConfig.asyncStreamTimeout(),
                        reconnectMetrics,
                        platformStateFacade),
                stateConfig,
                platformStateFacade,
                platformContext.getMerkleCryptography());
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        final ParallelExecutor shadowgraphExecutor = new CachedPoolParallelExecutor(threadManager, "node-sync");
        thingsToStart.add(shadowgraphExecutor);
        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics());
        syncShadowgraphSynchronizer = new ShadowgraphSynchronizer(
                platformContext,
                shadowgraph,
                roster.rosterEntries().size(),
                syncMetrics,
                event -> receivedEventHandler.accept(event),
                syncManager,
                intakeEventCounter,
                shadowgraphExecutor);

        reconnectController = new ReconnectController(reconnectConfig, threadManager, reconnectHelper, this::resume);

        final ProtocolConfig protocolConfig = platformContext.getConfiguration().getConfigData(ProtocolConfig.class);

        final Duration hangingThreadDuration = basicConfig.hangingThreadDuration();

        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = roster.rosterEntries().size() - 1;
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        syncPermitProvider = new SyncPermitProvider(platformContext, permitCount);

        buildSyncProtocolThreads(
                platformContext,
                threadManager,
                selfId,
                appVersion,
                latestCompleteState,
                syncMetrics,
                currentPlatformStatus::get,
                hangingThreadDuration,
                protocolConfig,
                reconnectConfig,
                platformStateFacade);

        thingsToStart.add(() -> syncProtocolThreads.forEach(StoppableThread::start));
    }

    private void buildSyncProtocolThreads(
            final PlatformContext platformContext,
            final ThreadManager threadManager,
            final NodeId selfId,
            final SoftwareVersion appVersion,
            final Supplier<ReservedSignedState> getLatestCompleteState,
            final SyncMetrics syncMetrics,
            final Supplier<PlatformStatus> platformStatusSupplier,
            final Duration hangingThreadDuration,
            final ProtocolConfig protocolConfig,
            final ReconnectConfig reconnectConfig,
            final PlatformStateFacade platformStateFacade) {

        final Protocol syncProtocol = new SyncProtocol(
                platformContext,
                syncShadowgraphSynchronizer,
                fallenBehindManager,
                syncPermitProvider,
                intakeEventCounter,
                gossipHalted::get,
                Duration.ZERO,
                syncMetrics,
                platformStatusSupplier);

        final Protocol reconnectProtocol = new ReconnectProtocol(
                platformContext,
                threadManager,
                reconnectThrottle,
                getLatestCompleteState,
                reconnectConfig.asyncStreamTimeout(),
                reconnectMetrics,
                reconnectController,
                new DefaultSignedStateValidator(platformContext, platformStateFacade),
                fallenBehindManager,
                platformStatusSupplier,
                platformStateFacade);

        final Protocol heartbeatProtocol = new HeartbeatProtocol(
                Duration.ofMillis(syncConfig.syncProtocolHeartbeatPeriod()), networkMetrics, platformContext.getTime());
        final VersionCompareHandshake versionCompareHandshake =
                new VersionCompareHandshake(appVersion, !protocolConfig.tolerateMismatchedVersion());
        final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);
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
                            new NegotiationProtocols(List.of(
                                    heartbeatProtocol.createPeerInstance(otherId),
                                    reconnectProtocol.createPeerInstance(otherId),
                                    syncProtocol.createPeerInstance(otherId))),
                            platformContext.getTime()))
                    .build());
        }
    }

    /**
     * Get the reconnect controller. This method is needed to break a circular dependency.
     */
    private ReconnectController getReconnectController() {
        return reconnectController;
    }

    /**
     * Start gossiping.
     */
    private void start() {
        if (started) {
            throw new IllegalStateException("Gossip already started");
        }
        started = true;
        thingsToStart.forEach(Startable::start);
    }

    /**
     * Stop gossiping.
     */
    private void stop() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        syncManager.haltRequestedObserver("stopping gossip");
        gossipHalted.set(true);
        // wait for all existing syncs to stop. no new ones will be started, since gossip has been halted, and
        // we've fallen behind
        syncPermitProvider.waitForAllPermitsToBeReleased();
        for (final StoppableThread thread : syncProtocolThreads) {
            thread.stop();
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
     * Stop gossiping until {@link #resume()} is called. If called when already paused then this has no effect.
     */
    private void pause() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        gossipHalted.set(true);
        syncPermitProvider.waitForAllPermitsToBeReleased();
    }

    /**
     * Resume gossiping. Undoes the effect of {@link #pause()}. Should be called exactly once after each call to
     * {@link #pause()}.
     */
    private void resume() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        intakeEventCounter.reset();
        gossipHalted.set(false);

        // Revoke all permits when we begin gossiping again. Presumably we are behind the pack,
        // and so we want to avoid talking to too many peers at once until we've had a chance
        // to properly catch up.
        syncPermitProvider.revokeAll();
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    private void clear() {
        shadowgraph.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(
            @NonNull final WiringModel model,
            @NonNull final BindableInputWire<PlatformEvent, Void> eventInput,
            @NonNull final BindableInputWire<EventWindow, Void> eventWindowInput,
            @NonNull final StandardOutputWire<PlatformEvent> eventOutput,
            @NonNull final BindableInputWire<NoInput, Void> startInput,
            @NonNull final BindableInputWire<NoInput, Void> stopInput,
            @NonNull final BindableInputWire<NoInput, Void> clearInput,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput) {

        startInput.bindConsumer(ignored -> start());
        stopInput.bindConsumer(ignored -> stop());
        clearInput.bindConsumer(ignored -> clear());

        eventInput.bindConsumer(shadowgraph::addEvent);
        eventWindowInput.bindConsumer(shadowgraph::updateEventWindow);

        systemHealthInput.bindConsumer(syncPermitProvider::reportUnhealthyDuration);
        platformStatusInput.bindConsumer(currentPlatformStatus::set);

        receivedEventHandler = eventOutput::forward;
    }
}
