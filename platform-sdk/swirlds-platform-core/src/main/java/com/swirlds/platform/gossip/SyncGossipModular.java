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
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise;
import com.swirlds.platform.network.protocol.StateSyncProtocol;
import com.swirlds.platform.network.protocol.SyncProtocol;
import com.swirlds.platform.network.protocol.rpc.RpcProtocol;
import com.swirlds.platform.reconnect.FallenBehindMonitor;
import com.swirlds.platform.reconnect.StateSyncThrottle;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.wiring.NoInput;
import com.swirlds.platform.wiring.components.Gossip;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.model.event.PlatformEvent;
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
    private final AbstractShadowgraphSynchronizer synchronizer;
    private final StateSyncProtocol stateSyncProtocol;

    // this is not a nice dependency, should be removed as well as the sharedState
    private Consumer<PlatformEvent> receivedEventHandler;

    /**
     * Builds the gossip engine, depending on which flavor is requested in the configuration.
     *
     * @param platformContext            the platform context
     * @param threadManager              the thread manager
     * @param ownKeysAndCerts            private keys and public certificates for this node
     * @param roster                     the current roster
     * @param selfId                     this node's ID
     * @param appVersion                 the version of the app
     * @param latestCompleteState        holds the latest signed state that has enough signatures to be verifiable
     * @param intakeEventCounter         keeps track of the number of events in the intake pipeline from each peer
     * @param platformStateFacade        the facade to access the platform state
     * @param reservedSignedStatePromise
     */
    public SyncGossipModular(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final KeysAndCerts ownKeysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final SemanticVersion appVersion,
            @NonNull final Function<String, ReservedSignedState> latestCompleteState,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            final ReservedSignedStatePromise reservedSignedStatePromise,
            final SwirldStateManager stateManager) {

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

        final ProtocolConfig protocolConfig = platformContext.getConfiguration().getConfigData(ProtocolConfig.class);

        final int rosterSize = peers.size() + 1;
        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics(), platformContext.getTime());

        if (protocolConfig.rpcGossip()) {

            final RpcShadowgraphSynchronizer rpcSynchronizer = new RpcShadowgraphSynchronizer(
                    platformContext,
                    rosterSize,
                    syncMetrics,
                    event -> receivedEventHandler.accept(event),
                    fallenBehindMonitor,
                    intakeEventCounter,
                    selfId);

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
                    fallenBehindMonitor,
                    intakeEventCounter,
                    new CachedPoolParallelExecutor(threadManager, "node-sync"));

            this.synchronizer = shadowgraphSynchronizer;

            this.syncProtocol = SyncProtocol.create(
                    platformContext, shadowgraphSynchronizer, intakeEventCounter, rosterSize, syncMetrics);
        }

        stateSyncProtocol = createReconnectProtocol(
                platformContext,
                threadManager,
                latestCompleteState,
                platformStateFacade,
                reservedSignedStatePromise,
                stateManager,
                fallenBehindMonitor);
        this.protocols = ImmutableList.of(
                HeartbeatProtocol.create(platformContext, this.network.getNetworkMetrics()),
                stateSyncProtocol,
                syncProtocol);

        final VersionCompareHandshake versionCompareHandshake =
                new VersionCompareHandshake(appVersion, !protocolConfig.tolerateMismatchedVersion());
        final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);

        network.initialize(threadManager, handshakeProtocols, protocols);
    }

    /**
     * Utility method for creating ReconnectProtocol from shared state, while staying compatible with pre-refactor code
     *
     * @param platformContext     the platform context
     * @param threadManager       the thread manager
     * @param latestCompleteState holds the latest signed state that has enough signatures to be verifiable
     * @param platformStateFacade the facade to access the platform state
     * @param stateManager
     * @param fallenBehindMonitor
     * @return constructed ReconnectProtocol
     */
    public StateSyncProtocol createReconnectProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Function<String, ReservedSignedState> latestCompleteState,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final ReservedSignedStatePromise reservedSignedStatePromise,
            final SwirldStateManager stateManager,
            final FallenBehindMonitor fallenBehindMonitor) {

        final ReconnectConfig reconnectConfig =
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class);

        final StateSyncThrottle stateSyncThrottle = new StateSyncThrottle(reconnectConfig, platformContext.getTime());

        final ReconnectMetrics reconnectMetrics = new ReconnectMetrics(platformContext.getMetrics());

        return new StateSyncProtocol(
                platformContext,
                threadManager,
                stateSyncThrottle,
                latestCompleteState,
                reconnectConfig.asyncStreamTimeout(),
                reconnectMetrics,
                platformStateFacade,
                reservedSignedStatePromise,
                stateManager,
                fallenBehindMonitor);
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
            @NonNull final BindableInputWire<NoInput, Void> pause,
            @NonNull final BindableInputWire<NoInput, Void> resume,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput) {

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

        pause.bindConsumer(ignored -> {
            syncProtocol.pause();
        });
        resume.bindConsumer(ignored -> {
            syncProtocol.resume();
        });
        this.receivedEventHandler = eventOutput::forward;
    }
}
