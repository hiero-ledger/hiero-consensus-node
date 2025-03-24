// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.google.common.collect.ImmutableList;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.gossip.FallenBehindManagerImpl;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.ProtocolConfig;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.communication.handshake.VersionCompareHandshake;
import com.swirlds.platform.network.protocol.*;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Utility class used during refactoring; with time, it should disappear, as all things will move to main wiring as all
 * shared state is resolved
 */
public class SyncGossipModular implements Gossip {

    private static final Logger logger = LogManager.getLogger(SyncGossipModular.class);

    private final SyncGossipController controller;
    private final PeerCommunication network;
    private final SyncPermitProvider syncPermitProvider;
    private final FallenBehindManagerImpl fallenBehindManager;
    private SyncGossipSharedProtocolState sharedState;

    // this is not a nice dependency, should be removed as well as the sharedState
    private Consumer<PlatformEvent> receivedEventHandler;

    /**
     * Builds the gossip engine, depending on which flavor is requested in the configuration.
     *
     * @param platformContext               the platform context
     * @param threadManager                 the thread manager
     * @param ownKeysAndCerts               private keys and public certificates for this node
     * @param roster                        the current roster
     * @param selfId                        this node's ID
     * @param appVersion                    the version of the app
     * @param swirldStateManager            manages the mutable state
     * @param latestCompleteState           holds the latest signed state that has enough signatures to be verifiable
     * @param statusActionSubmitter         for submitting updates to the platform status manager
     * @param loadReconnectState            a method that should be called when a state from reconnect is obtained
     * @param clearAllPipelinesForReconnect this method should be called to clear all pipelines prior to a reconnect
     * @param intakeEventCounter            keeps track of the number of events in the intake pipeline from each peer
     */
    public SyncGossipModular(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final KeysAndCerts ownKeysAndCerts,
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

        final RosterEntry selfEntry = RosterUtils.getRosterEntry(roster, selfId.id());
        final X509Certificate selfCert = RosterUtils.fetchGossipCaCertificate(selfEntry);
        final List<PeerInfo> peers;
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
        final PeerInfo selfPeer = Utilities.toPeerInfo(selfEntry);

        this.network = new PeerCommunication(platformContext, peers, selfPeer, ownKeysAndCerts);

        final Shadowgraph shadowgraph = new Shadowgraph(platformContext, peers.size() + 1, intakeEventCounter);

        this.fallenBehindManager = new FallenBehindManagerImpl(
                selfId,
                peers.size(),
                statusActionSubmitter,
                () -> sharedState.fallenBehindCallback().get().run(),
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class));

        final SyncManagerImpl syncManager = new SyncManagerImpl(platformContext, fallenBehindManager);

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = peers.size();
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        this.syncPermitProvider = new SyncPermitProvider(platformContext, permitCount);

        sharedState = new SyncGossipSharedProtocolState(
                this.network.getNetworkMetrics(),
                syncPermitProvider,
                shadowgraph,
                syncManager,
                new AtomicBoolean(false),
                new CachedPoolParallelExecutor(threadManager, "node-sync"),
                new AtomicReference<>(PlatformStatus.STARTING_UP),
                event -> this.receivedEventHandler.accept(event),
                new AtomicReference<>());

        this.controller = new SyncGossipController(intakeEventCounter, sharedState);

        final List<Protocol> protocols = ImmutableList.of(
                HeartbeatProtocol.create(platformContext, sharedState.networkMetrics()),
                ReconnectProtocol.create(
                        platformContext,
                        sharedState,
                        threadManager,
                        latestCompleteState,
                        roster,
                        loadReconnectState,
                        clearAllPipelinesForReconnect,
                        swirldStateManager,
                        selfId,
                        controller,
                        platformStateFacade),
                SyncProtocol.create(platformContext, sharedState, intakeEventCounter, peers.size() + 1));

        final ProtocolConfig protocolConfig = platformContext.getConfiguration().getConfigData(ProtocolConfig.class);
        final VersionCompareHandshake versionCompareHandshake =
                new VersionCompareHandshake(appVersion, !protocolConfig.tolerateMismatchedVersion());
        final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);

        network.initialize(threadManager, handshakeProtocols, protocols);
        controller.registerThingToStart(sharedState.shadowgraphExecutor());
    }

    /**
     * Modify list of current connected peers. Notify all underlying components and start needed threads. In the case
     * data for the same peer changes (one with the same nodeId), it should be present in both removed and added lists,
     * with old data in removed and fresh data in added. Internally it will be first removed and then added, so there
     * can be a short moment when it will drop out of the network if disconnect happens at a bad moment. NOT THREAD
     * SAFE. Synchronize externally.
     *
     * @param added   peers to be added
     * @param removed peers to be removed
     */
    public void addRemovePeers(@NonNull final List<PeerInfo> added, @NonNull final List<PeerInfo> removed) {
        synchronized (this) {
            fallenBehindManager.addRemovePeers(
                    added.stream().map(PeerInfo::nodeId).collect(Collectors.toSet()),
                    removed.stream().map(PeerInfo::nodeId).collect(Collectors.toSet()));
            syncPermitProvider.adjustTotalPermits(added.size() - removed.size());
            network.addRemovePeers(added, removed);
        }
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

        startInput.bindConsumer(ignored -> {
            controller.start();
            network.start();
        });
        stopInput.bindConsumer(ignored -> {
            controller.stop();
            network.stop();
        });
        clearInput.bindConsumer(ignored -> controller.clear());

        eventInput.bindConsumer(sharedState.shadowgraph()::addEvent);
        eventWindowInput.bindConsumer(sharedState.shadowgraph()::updateEventWindow);

        systemHealthInput.bindConsumer(sharedState.syncPermitProvider()::reportUnhealthyDuration);
        platformStatusInput.bindConsumer(sharedState.currentPlatformStatus()::set);

        receivedEventHandler = eventOutput::forward;
    }
}
