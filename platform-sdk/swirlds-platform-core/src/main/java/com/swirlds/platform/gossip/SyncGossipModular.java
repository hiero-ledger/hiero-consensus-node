// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.google.common.collect.ImmutableList;
import com.hedera.hapi.node.base.SemanticVersion;
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
import com.swirlds.platform.gossip.shadowgraph.AbstractShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.RpcShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.PeerCommunication;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.communication.handshake.VersionCompareHandshake;
import com.swirlds.platform.network.protocol.AbstractSyncProtocol;
import com.swirlds.platform.network.protocol.HeartbeatProtocol;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.network.protocol.ReconnectStateSyncProtocol;
import com.swirlds.platform.network.protocol.ReservedSignedStateResultPromise;
import com.swirlds.platform.network.protocol.SyncProtocol;
import com.swirlds.platform.network.protocol.rpc.RpcProtocol;
import com.swirlds.platform.reconnect.FallenBehindMonitor;
import com.swirlds.platform.reconnect.ReconnectStateTeacherThrottle;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.wiring.NoInput;
import com.swirlds.platform.wiring.components.Gossip;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Utility class for wiring various subcomponents of gossip module. In particular, it abstracts away specific protocols
 * from network component using them and connects all of these to wiring framework.
 */
public class SyncGossipModular implements Gossip {

    private static final Logger logger = LogManager.getLogger(SyncGossipModular.class);

    private final PeerCommunication network;
    private final ImmutableList<Protocol> protocols;
    private final AbstractSyncProtocol<?> syncProtocol;
    private final FallenBehindMonitor fallenBehindMonitor;
    private final AbstractShadowgraphSynchronizer synchronizer;
    private final StateLifecycleManager stateLifecycleManager;
    private final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap;

    // this is not a nice dependency, should be removed as well as the sharedState
    private Consumer<PlatformEvent> receivedEventHandler;
    private Consumer<SyncProgress> syncProgressHandler;

    /**
     * Builds the gossip engine, depending on which flavor is requested in the configuration.
     *
     * @param platformContext               the platform context
     * @param threadManager                 the thread manager
     * @param ownKeysAndCerts               private keys and public certificates for this node
     * @param roster                        the current roster
     * @param selfId                        this node's ID
     * @param appVersion                    the version of the app
     * @param stateLifecycleManager            manages the mutable state
     * @param latestCompleteState           holds the latest signed state that has enough signatures to be verifiable
     * @param intakeEventCounter            keeps track of the number of events in the intake pipeline from each peer
     * @param createStateFromVirtualMap     a function to instantiate the state object from a Virtual Map
     * @param fallenBehindMonitor           an instance of the fallenBehind Monitor which tracks if the node has fallen behind
     * @param reservedSignedStateResultPromise a mechanism to get a SignedState or block while it is not available
     */
    public SyncGossipModular(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final KeysAndCerts ownKeysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final SemanticVersion appVersion,
            @NonNull final StateLifecycleManager stateLifecycleManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final ReservedSignedStateResultPromise reservedSignedStateResultPromise) {

        final RosterEntry selfEntry = RosterUtils.getRosterEntry(roster, selfId.id());
        final X509Certificate selfCert = RosterUtils.fetchGossipCaCertificate(selfEntry);
        final List<PeerInfo> peers;
        if (!CryptoUtils.checkCertificate(selfCert)) {
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

        this.fallenBehindMonitor = fallenBehindMonitor;
        this.stateLifecycleManager = stateLifecycleManager;
        this.createStateFromVirtualMap = createStateFromVirtualMap;

        final ProtocolConfig protocolConfig = platformContext.getConfiguration().getConfigData(ProtocolConfig.class);

        final int rosterSize = peers.size() + 1;
        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics(), platformContext.getTime(), peers);

        if (protocolConfig.rpcGossip()) {

            final RpcShadowgraphSynchronizer rpcSynchronizer = new RpcShadowgraphSynchronizer(
                    platformContext,
                    rosterSize,
                    syncMetrics,
                    event -> receivedEventHandler.accept(event),
                    fallenBehindMonitor,
                    intakeEventCounter,
                    selfId,
                    lag -> syncProgressHandler.accept(lag));

            this.synchronizer = rpcSynchronizer;

            this.syncProtocol = RpcProtocol.create(
                    platformContext,
                    rpcSynchronizer,
                    intakeEventCounter,
                    threadManager,
                    rosterSize,
                    this.network.getNetworkMetrics(),
                    syncMetrics);

        } else {
            final Shadowgraph shadowgraph = new Shadowgraph(platformContext, rosterSize, intakeEventCounter);

            final ShadowgraphSynchronizer shadowgraphSynchronizer = new ShadowgraphSynchronizer(
                    platformContext,
                    shadowgraph,
                    rosterSize,
                    syncMetrics,
                    event -> receivedEventHandler.accept(event),
                    this.fallenBehindMonitor,
                    intakeEventCounter,
                    new CachedPoolParallelExecutor(threadManager, "node-sync"),
                    lag -> syncProgressHandler.accept(lag));

            this.synchronizer = shadowgraphSynchronizer;

            this.syncProtocol = SyncProtocol.create(
                    platformContext,
                    shadowgraphSynchronizer,
                    this.fallenBehindMonitor,
                    intakeEventCounter,
                    rosterSize,
                    syncMetrics);
        }

        final ReconnectStateSyncProtocol reconnectStateSyncProtocol = createStateSyncProtocol(
                platformContext, threadManager, latestCompleteState, reservedSignedStateResultPromise);
        this.protocols = ImmutableList.of(
                HeartbeatProtocol.create(platformContext, this.network.getNetworkMetrics()),
                reconnectStateSyncProtocol,
                syncProtocol);

        final VersionCompareHandshake versionCompareHandshake =
                new VersionCompareHandshake(appVersion, !protocolConfig.tolerateMismatchedVersion());
        final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);

        network.initialize(threadManager, handshakeProtocols, protocols);
    }

    /**
     * Utility method for creating ReconnectProtocol from shared state, while staying compatible with pre-refactor code
     *
     * @param platformContext               the platform context
     * @param threadManager                 the thread manager
     * @param latestCompleteState           holds the latest signed state that has enough signatures to be verifiable
     * @return constructed ReconnectProtocol
     */
    public ReconnectStateSyncProtocol createStateSyncProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final ReservedSignedStateResultPromise reservedSignedStateResultPromise) {

        final ReconnectConfig reconnectConfig =
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class);

        final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle =
                new ReconnectStateTeacherThrottle(reconnectConfig, platformContext.getTime());

        final ReconnectMetrics reconnectMetrics = new ReconnectMetrics(platformContext.getMetrics());

        return new ReconnectStateSyncProtocol(
                platformContext,
                threadManager,
                reconnectStateTeacherThrottle,
                latestCompleteState,
                reconnectConfig.asyncStreamTimeout(),
                reconnectMetrics,
                fallenBehindMonitor,
                reservedSignedStateResultPromise,
                stateLifecycleManager,
                createStateFromVirtualMap);
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
            // if this is needed we should update fallenBehindMonitor
            syncProtocol.adjustTotalPermits(
                    added.size() - removed.size()); // Review, needs to make sure that the removed are part of the AB?
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
            @NonNull final BindableInputWire<NoInput, Void> pauseGossip,
            @NonNull final BindableInputWire<NoInput, Void> resumeGossip,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput,
            @NonNull final StandardOutputWire<SyncProgress> syncLagOutput) {

        startInput.bindConsumer(ignored -> {
            syncProtocol.start();
            network.start();
        });
        stopInput.bindConsumer(ignored -> {
            syncProtocol.stop();
            network.stop();
        });

        clearInput.bindConsumer(ignored -> syncProtocol.clear());
        eventInput.bindConsumer(synchronizer::addEvent);
        eventWindowInput.bindConsumer(synchronizer::updateEventWindow);

        systemHealthInput.bindConsumer(syncProtocol::reportUnhealthyDuration);
        platformStatusInput.bindConsumer(status -> {
            protocols.forEach(protocol -> protocol.updatePlatformStatus(status));
        });
        pauseGossip.bindConsumer(ignored -> {
            syncProtocol.pause();
            fallenBehindMonitor.notifySyncProtocolPaused();
        });
        resumeGossip.bindConsumer(ignored -> {
            syncProtocol.resume();
        });
        this.receivedEventHandler = eventOutput::forward;
        this.syncProgressHandler = syncLagOutput::forward;
    }
}
