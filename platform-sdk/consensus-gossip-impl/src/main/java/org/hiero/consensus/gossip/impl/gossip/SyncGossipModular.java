// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.CachedPoolParallelExecutor;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.config.ProtocolConfig;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowgraphSynchronizer;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.gossip.impl.network.PeerCommunication;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.gossip.impl.network.communication.handshake.VersionCompareHandshake;
import org.hiero.consensus.gossip.impl.network.protocol.HeartbeatProtocol;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.gossip.impl.network.protocol.ProtocolRunnable;
import org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcProtocol;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Utility class for wiring various subcomponents of gossip module. In particular, it abstracts away specific protocols
 * from network component using them and connects all of these to wiring framework.
 */
public class SyncGossipModular implements Gossip {

    private static final Logger logger = LogManager.getLogger(SyncGossipModular.class);

    private final PeerCommunication network;
    private final List<Protocol> protocols;
    private final RpcProtocol rpcProtocol;
    private final FallenBehindMonitor fallenBehindMonitor;
    private final ShadowgraphSynchronizer synchronizer;

    // this is not a nice dependency, should be removed as well as the sharedState
    private Consumer<PlatformEvent> receivedEventHandler;
    private Consumer<SyncProgress> syncProgressHandler;

    /**
     * Builds the gossip engine, depending on which flavor is requested in the configuration.
     *
     * @param configuration the configuration
     * @param metrics the metrics registry
     * @param time the time source
     * @param threadManager the thread manager
     * @param ownKeysAndCerts private keys and public certificates for this node
     * @param roster the current roster
     * @param selfId this node's ID
     * @param appVersion the version of the app
     * @param intakeEventCounter keeps track of the number of events in the intake pipeline from each peer
     * @param fallenBehindMonitor an instance of the fallenBehind Monitor which tracks if the node has fallen behind
     * @param reconnectProtocol the reconnect protocol to use
     */
    public SyncGossipModular(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final KeysAndCerts ownKeysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final SemanticVersion appVersion,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final Protocol reconnectProtocol) {

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

        this.network = new PeerCommunication(configuration, metrics, time, peers, selfPeer, ownKeysAndCerts);

        this.fallenBehindMonitor = fallenBehindMonitor;

        final ProtocolConfig protocolConfig = configuration.getConfigData(ProtocolConfig.class);

        final int rosterSize = peers.size() + 1;
        final SyncMetrics syncMetrics = new SyncMetrics(metrics, time, peers);

        final ShadowgraphSynchronizer rpcSynchronizer = new ShadowgraphSynchronizer(
                configuration,
                metrics,
                time,
                rosterSize,
                syncMetrics,
                intakeEventCounter,
                lag -> syncProgressHandler.accept(lag));

        this.synchronizer = rpcSynchronizer;

        this.rpcProtocol = new RpcProtocol(
                configuration,
                metrics,
                time,
                rpcSynchronizer,
                new CachedPoolParallelExecutor(threadManager, "node-rpc-sync"),
                intakeEventCounter,
                rosterSize,
                this.network.getNetworkMetrics(),
                syncMetrics,
                selfId,
                fallenBehindMonitor,
                event -> receivedEventHandler.accept(event));

        this.protocols = List.of(
                HeartbeatProtocol.create(configuration, time, this.network.getNetworkMetrics()),
                reconnectProtocol,
                rpcProtocol);

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
     * @param added peers to be added
     * @param removed peers to be removed
     */
    public void addRemovePeers(@NonNull final List<PeerInfo> added, @NonNull final List<PeerInfo> removed) {
        synchronized (this) {
            // if this is needed we should update fallenBehindMonitor
            rpcProtocol.adjustTotalPermits(
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
            rpcProtocol.start();
            network.start();
        });
        stopInput.bindConsumer(ignored -> {
            rpcProtocol.stop();
            network.stop();
        });

        clearInput.bindConsumer(ignored -> rpcProtocol.clear());
        eventInput.bindConsumer(event -> {
            rpcProtocol.addEvent(event);
            synchronizer.addEvent(event);
        });
        eventWindowInput.bindConsumer(synchronizer::updateEventWindow);

        systemHealthInput.bindConsumer(rpcProtocol::reportUnhealthyDuration);
        platformStatusInput.bindConsumer(status -> {
            protocols.forEach(protocol -> protocol.updatePlatformStatus(status));
        });
        pauseGossip.bindConsumer(ignored -> {
            rpcProtocol.pause();
            fallenBehindMonitor.notifySyncProtocolPaused();
        });
        resumeGossip.bindConsumer(ignored -> {
            rpcProtocol.resume();
        });
        this.receivedEventHandler = eventOutput::forward;
        this.syncProgressHandler = syncLagOutput::forward;
    }
}
