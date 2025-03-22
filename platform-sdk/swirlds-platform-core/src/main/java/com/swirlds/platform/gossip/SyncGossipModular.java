// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.google.common.collect.ImmutableList;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.network.PeerCommunication;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.communication.handshake.VersionCompareHandshake;
import com.swirlds.platform.network.protocol.HeartbeatProtocol;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.network.protocol.ReconnectProtocol;
import com.swirlds.platform.network.protocol.SyncProtocol;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.status.PlatformStatus;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.wiring.NoInput;
import com.swirlds.platform.wiring.components.Gossip;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for wiring various subcomponents of gossip module. In particular, it abstracts away
 * specific protocols from network component using them and connects all of these to wiring framework.
 */
public class SyncGossipModular implements Gossip {

    private static final Logger logger = LogManager.getLogger(SyncGossipModular.class);

    private final PeerCommunication network;
    private final ImmutableList<Protocol> protocols;
    private final SyncProtocol syncProtocol;
    private final SyncManagerImpl syncManager;

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

        this.syncManager = new SyncManagerImpl(
                platformContext,
                new FallenBehindManagerImpl(
                        selfId,
                        peers.size(),
                        statusActionSubmitter,
                        platformContext.getConfiguration().getConfigData(ReconnectConfig.class)));

        this.syncProtocol = SyncProtocol.create(
                platformContext,
                syncManager,
                event -> receivedEventHandler.accept(event),
                intakeEventCounter,
                threadManager,
                peers.size() + 1);

        this.protocols = ImmutableList.of(
                HeartbeatProtocol.create(platformContext, this.network.getNetworkMetrics()),
                ReconnectProtocol.create(
                        platformContext,
                        syncManager,
                        threadManager,
                        latestCompleteState,
                        roster,
                        loadReconnectState,
                        clearAllPipelinesForReconnect,
                        swirldStateManager,
                        selfId,
                        this.syncProtocol,
                        platformStateFacade),
                syncProtocol);

        final ProtocolConfig protocolConfig = platformContext.getConfiguration().getConfigData(ProtocolConfig.class);
        final VersionCompareHandshake versionCompareHandshake =
                new VersionCompareHandshake(appVersion, !protocolConfig.tolerateMismatchedVersion());
        final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);

        network.initialize(threadManager, handshakeProtocols, protocols);
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
            syncManager.addRemovePeers(
                    added.stream().map(PeerInfo::nodeId).collect(Collectors.toSet()),
                    removed.stream().map(PeerInfo::nodeId).collect(Collectors.toSet()));
            syncProtocol.adjustTotalPermits(added.size() - removed.size());
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
            syncProtocol.start();
            network.start();
        });
        stopInput.bindConsumer(ignored -> {
            syncProtocol.stop();
            network.stop();
        });

        clearInput.bindConsumer(ignored -> syncProtocol.clear());
        eventInput.bindConsumer(syncProtocol::addEvent);
        eventWindowInput.bindConsumer(syncProtocol::updateEventWindow);

        systemHealthInput.bindConsumer(syncProtocol::reportUnhealthyDuration);
        platformStatusInput.bindConsumer(
                status -> protocols.forEach(protocol -> protocol.updatePlatformStatus(status)));

        this.receivedEventHandler = eventOutput::forward;
    }
}
