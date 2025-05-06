// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getMyTipsTheyKnow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getTheirTipsIHave;
import static org.hiero.base.CompareTo.isGreaterThanOrEqualTo;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiver;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * The goal of the GossipRpcShadowgraphSynchronizer is to compare graphs with a remote node, and update them so both
 * sides have the same events in the graph. This process is called a sync.
 * <p>
 * This class is designed to use message based protocol (as a precursor to RPC communication) instead of stealing entire
 * socket (unlike {@link ShadowgraphSynchronizer}
 */
public class GossipRpcShadowgraphSynchronizer extends AbstractShadowgraphSynchronizer {

    private static final Logger logger = LogManager.getLogger(GossipRpcShadowgraphSynchronizer.class);

    /**
     * List of all started sync exchanges with remote peers
     */
    private final List<RpcPeerState> allRpcPeers = new CopyOnWriteArrayList<>();

    /**
     * Our own node id
     */
    private final NodeId selfId;

    /**
     * How long should we wait between sync attempts
     */
    private final Duration syncPeriod;

    /**
     * Configuration for various sync parameters
     */
    private final SyncConfig syncConfig;

    /**
     * Constructs a new ShadowgraphSynchronizer.
     *
     * @param platformContext      the platform context
     * @param shadowGraph          stores events to sync
     * @param numberOfNodes        number of nodes in the network
     * @param syncMetrics          metrics for sync
     * @param receivedEventHandler events that are received are passed here
     * @param fallenBehindManager  tracks if we have fallen behind
     * @param intakeEventCounter   used for tracking events in the intake pipeline per peer
     * @param selfId               id of current node
     */
    public GossipRpcShadowgraphSynchronizer(
            @NonNull final PlatformContext platformContext,
            @NonNull final Shadowgraph shadowGraph,
            final int numberOfNodes,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final NodeId selfId) {

        super(
                platformContext,
                shadowGraph,
                numberOfNodes,
                syncMetrics,
                receivedEventHandler,
                fallenBehindManager,
                intakeEventCounter);
        this.syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        this.selfId = selfId;
        this.syncPeriod = syncConfig.syncPeriod();
    }

    /**
     * Create new state class for the RPC peer
     *
     * @param sender      endpoint for sending messages to remote endpoint asynchronously
     * @param otherNodeId id of the remote node
     * @return rpc peer state object
     */
    public RpcPeerState createPeerState(@NonNull final GossipRpcSender sender, @NonNull final NodeId otherNodeId) {
        final RpcPeerState rpcPeerState = new RpcPeerState(sender, otherNodeId, syncPeriod);
        allRpcPeers.add(rpcPeerState);
        return rpcPeerState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        super.addEvent(platformEvent);

        // broadcast event to other nodes as part of simplistic broadcast
        if (syncConfig.broadcast() && selfId.equals(platformEvent.getCreatorId())) {
            final GossipEvent gossipEvent = platformEvent.getGossipEvent();
            allRpcPeers.forEach(rpcPeer -> rpcPeer.broadcastEvent(gossipEvent));
            syncMetrics.broadcastEventSent();
        }
    }

    /**
     * State class for a RPC exchange between two nodes.
     */
    // TODO: Refactor into reader, writer and shared state
    // Heavily experimental atm, please don't review

    public class RpcPeerState implements GossipRpcReceiver {

        /**
         * Events they for sure have based on the tips they have sent, to be merged with known tips they respond for out
         * tipset
         */
        private final Set<ShadowEvent> eventsTheyHave = new HashSet<>();

        /**
         * Endpoint for queuing messages to be sent to the other side
         */
        private final GossipRpcSender sender;

        /**
         * Node id of the remote side
         */
        private final NodeId otherNodeId;

        /**
         * Amount of time to sleep between sync attempts
         */
        private final Duration sleepAfterSync;

        /**
         * Event window we have reserved during start of sync process with remote node
         */
        private ReservedEventWindow shadowWindow;

        /**
         * Sync data we have sent to remote party
         */
        private SyncData mySyncData;

        /**
         * Sync data we have received from remote party
         */
        private SyncData remoteSyncData;

        /**
         * List of tips we have sent to remote party
         */
        private List<ShadowEvent> myTips;

        /**
         * Has remote node reported it has fallen behind
         */
        private volatile boolean remoteFallenBehind;

        /**
         * What was the time we have finished sync last
         */
        private volatile Instant lastSyncTime = Instant.MIN;

        /**
         * Remote party is still sending us events, so we shouldn't bother it with another sync request
         */
        private volatile boolean remoteStillSendingEvents = false;

        /**
         * How many events were sent out to remote node during latest sync
         */
        private int outgoingEventsCounter = 0;

        /**
         * How many events were received from remote node during latest sync
         */
        private int incomingEventsCounter = 0;

        private final Object stateMonitor = new Object();

        /**
         * Create new state class for a RPC peer
         *
         * @param sender         endpoint for sending messages to remote endpoint asynchronously
         * @param otherNodeId    id of the remote node
         * @param sleepAfterSync amount of time to sleep between sync attempts
         */
        public RpcPeerState(
                @NonNull final GossipRpcSender sender,
                @NonNull final NodeId otherNodeId,
                @NonNull final Duration sleepAfterSync) {
            this.sender = sender;
            this.otherNodeId = otherNodeId;
            this.sleepAfterSync = Objects.requireNonNull(sleepAfterSync);
        }

        /**
         * Start synchronization with remote side, if all checks are successful (things like enough time has passed
         * since last synchronization, remote side has not fallen behind etc
         */
        // writer-thread
        public void possiblyStartSync() {
            if (!isSyncCooldownComplete()) {
                syncMetrics.doNotSyncCooldown();
                return;
            }

            if (remoteFallenBehind) {
                syncMetrics.doNotSyncRemoteFallenBehind();
                return;
            }

            if (remoteStillSendingEvents) {
                syncMetrics.setDoNotSyncRemoteProcessingEvents();
                return;
            }

            if (intakeEventCounter.hasUnprocessedEvents(otherNodeId)) {
                syncMetrics.doNotSyncIntakeCounter();
                return;
            }

            synchronized (stateMonitor) {
                if (this.mySyncData == null) {
                    sendSyncData();
                } else {
                    syncMetrics.setDoNotSyncAlreadyStarted();
                }
            }
        }

        /**
         * Clean all resources and deregister itself
         */
        // platform thread
        public void cleanup() {
            synchronized (stateMonitor) {
                clearInternalState();
            }
            GossipRpcShadowgraphSynchronizer.this.allRpcPeers.remove(this);
        }

        /**
         * Send event to remote node outside of normal sync logic (most probably due to broadcast)
         *
         * @param gossipEvent event to be sent
         */
        // platform thread
        void broadcastEvent(@NonNull final GossipEvent gossipEvent) {
            // don't spam remote side if it is going to reconnect
            // or if we haven't completed even a first sync, as it might be a recovery phase for either for us
            if (!this.remoteFallenBehind && this.lastSyncTime != Instant.MIN) {
                sender.sendBroadcastEvent(gossipEvent);
            }
        }

        // HANDLE INCOMING MESSAGES

        /**
         * {@inheritDoc}
         */
        // reader-thread
        @Override
        public void receiveSyncData(@NonNull final SyncData syncMessage) {

            syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_WINDOWS);
            syncMetrics.acceptedSyncRequest();

            synchronized (stateMonitor) {
                // if they are sending us sync data, they are no longer falling behind, nor sending events
                this.remoteFallenBehind = false;
                this.remoteStillSendingEvents = false;

                this.remoteSyncData = syncMessage;

                if (this.mySyncData == null) {
                    return;
                }

                bothSentSyncData();
            }
        }

        // both-threads
        @GuardedBy("stateMonitor")
        private void bothSentSyncData() {

            syncMetrics.eventWindow(mySyncData.eventWindow(), remoteSyncData.eventWindow());

            final SyncFallenBehindStatus behindStatus =
                    fallenBehind(mySyncData.eventWindow(), remoteSyncData.eventWindow(), otherNodeId);
            if (behindStatus != SyncFallenBehindStatus.NONE_FALLEN_BEHIND) {
                logger.info(
                        LogMarker.RECONNECT.getMarker(),
                        "{} local ev={} remote ev={}",
                        behindStatus,
                        mySyncData.eventWindow(),
                        remoteSyncData.eventWindow());
                clearInternalState();
                if (behindStatus == SyncFallenBehindStatus.OTHER_FALLEN_BEHIND) {
                    syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.OTHER_FALLEN_BEHIND);
                    this.remoteFallenBehind = true;
                } else {
                    syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.SELF_FALLEN_BEHIND);
                    sender.breakConversation();
                }
                return;
            }

            syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_TIPS);

            sendKnownTips();
        }

        /**
         * {@inheritDoc}
         */
        // reader-thread
        @Override
        public void receiveTips(@NonNull final List<Boolean> remoteTipKnowledge) {

            // Add each tip they know to the known set
            final List<ShadowEvent> knownTips = getMyTipsTheyKnow(otherNodeId, myTips, remoteTipKnowledge);

            synchronized (stateMonitor) {
                eventsTheyHave.addAll(knownTips);
                syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_EVENTS);
            }

            // this is one of two important parts of the code to keep outside of critical section - sending events

            // create a send list based on the known set
            final List<PlatformEvent> sendList =
                    createSendList(selfId, eventsTheyHave, mySyncData.eventWindow(), remoteSyncData.eventWindow());
            sender.sendEvents(
                    sendList.stream().map(PlatformEvent::getGossipEvent).collect(Collectors.toList()));
            outgoingEventsCounter += sendList.size();
            sender.sendEndOfEvents().thenRun(this::finishedSendingEvents);
        }

        /**
         * {@inheritDoc}
         */
        // reader-thread
        @Override
        public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
            // this is one of two important parts of the code to keep outside of critical section - receiving events
            final long start = System.nanoTime();
            incomingEventsCounter += gossipEvents.size();
            gossipEvents.forEach(this::handleIncomingSyncEvent);
            syncMetrics.eventsReceived(start, gossipEvents.size());
        }

        /**
         * {@inheritDoc}
         */
        // reader-thread
        @Override
        public void receiveEventsFinished() {
            synchronized (stateMonitor) {
                if (this.mySyncData == null) {
                    // have we already finished sending out events? if yes, mark the sync as finished
                    reportSyncFinished();
                } else {
                    syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.SENDING_EVENTS);
                }
                this.remoteStillSendingEvents = false;
            }
        }

        // reader-thread
        @Override
        public void receiveBroadcastEvent(final GossipEvent gossipEvent) {
            // we don't use handleIncomingSyncEvent, as we don't want to block sync till this event is resolved
            // so no marking it in intakeEventCounter
            syncMetrics.broadcastEventReceived();
            final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
            eventHandler.accept(platformEvent);
        }

        // UTILITY METHODS

        // writer-thread
        @GuardedBy("stateMonitor")
        private void sendSyncData() {
            syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_WINDOWS);
            this.shadowWindow = shadowGraph.reserve();
            this.myTips = getTips();
            final List<Hash> tipHashes =
                    myTips.stream().map(ShadowEvent::getEventBaseHash).collect(Collectors.toList());
            this.mySyncData = new SyncData(this.shadowWindow.getEventWindow(), tipHashes);
            sender.sendSyncData(this.mySyncData);
            syncMetrics.outgoingSyncRequestSent();
            if (this.remoteSyncData != null) {
                bothSentSyncData();
            }
        }

        // both-threads
        @GuardedBy("stateMonitor")
        private void sendKnownTips() {

            // process the hashes received
            final List<ShadowEvent> theirTips = shadowGraph.shadows(remoteSyncData.tipHashes());

            // For each tip they send us, determine if we have that event.
            // For each tip, send true if we have the event and false if we don't.
            final List<Boolean> theirTipsIHave = getTheirTipsIHave(theirTips);

            // Add their tips to the set of events they are known to have
            theirTips.stream().filter(Objects::nonNull).forEach(eventsTheyHave::add);

            remoteStillSendingEvents = true;

            sender.sendTips(theirTipsIHave);
        }

        // writer-thread
        private void finishedSendingEvents() {
            synchronized (stateMonitor) {
                if (!remoteStillSendingEvents) {
                    // have they already finished sending their events ? if yes, mark the sync as finished
                    reportSyncFinished();
                } else {
                    syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.RECEIVING_EVENTS);
                }
                clearInternalState();
            }
        }

        // both-threads
        @GuardedBy("stateMonitor")
        private void reportSyncFinished() {
            syncMetrics.syncDone(new SyncResult(false, otherNodeId, incomingEventsCounter, outgoingEventsCounter));
            incomingEventsCounter = 0;
            outgoingEventsCounter = 0;
            syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.IDLE);
        }

        // all threads
        @GuardedBy("stateMonitor")
        private void clearInternalState() {
            if (this.shadowWindow != null) {
                this.shadowWindow.close();
                this.shadowWindow = null;
            }
            this.remoteSyncData = null;
            this.mySyncData = null;
            this.myTips = null;
            eventsTheyHave.clear();
            this.lastSyncTime = time.now();
        }

        /**
         * @return true if the cooldown period after a sync has elapsed, else false
         */
        // writer-thread
        private boolean isSyncCooldownComplete() {
            final Duration elapsed = Duration.between(lastSyncTime, time.now());
            return isGreaterThanOrEqualTo(elapsed, sleepAfterSync);
        }

        /**
         * Propagate single event down the intake pipeline
         *
         * @param gossipEvent event received from the remote peer
         */
        // reader-thread
        private void handleIncomingSyncEvent(@NonNull final GossipEvent gossipEvent) {
            final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
            platformEvent.setSenderId(otherNodeId);
            intakeEventCounter.eventEnteredIntakePipeline(otherNodeId);
            eventHandler.accept(platformEvent);
        }
    }
}
