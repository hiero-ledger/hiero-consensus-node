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
     * List of all started sync conversations
     */
    private final List<SyncConversation> allConversations = new CopyOnWriteArrayList<>();

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
     * Create new state class for a RPC sync conversation between two nodes.
     *
     * @param sender      endpoint for sending messages to remote endpoint asynchronously
     * @param selfId      id of current node
     * @param otherNodeId id of the remote node
     * @return conversation state object
     */
    public SyncConversation synchronize(
            @NonNull final GossipRpcSender sender, @NonNull final NodeId selfId, @NonNull final NodeId otherNodeId) {
        final SyncConversation conversation = new SyncConversation(sender, selfId, otherNodeId, syncPeriod);
        allConversations.add(conversation);
        return conversation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        super.addEvent(platformEvent);

        // broadcast event to other nodes as part of simplistic chatter
        if (syncConfig.chatter() && selfId.equals(platformEvent.getCreatorId())) {
            final GossipEvent gossipEvent = platformEvent.getGossipEvent();
            allConversations.forEach(conversation -> conversation.broadcastEvent(gossipEvent));
            syncMetrics.chatterEventSent();
        }
    }

    /**
     * State class for a RPC sync conversation between two nodes.
     */
    public class SyncConversation implements GossipRpcReceiver {

        private final Set<ShadowEvent> eventsTheyHave = new HashSet<>();
        private final GossipRpcSender sender;
        private final NodeId otherNodeId;
        private final NodeId selfId;
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
         * Has remote node reported it has falled behind
         */
        private boolean remoteFallenBehind;

        /**
         * What was the time we have finished sync last
         */
        private Instant lastSyncTime = Instant.MIN;

        /**
         * Remote party is still sending us events, so we shouldn't bother it with another sync request
         */
        private boolean remoteStillSendingEvents = false;

        private int chatterIncomingCounter = 0;
        private int syncIncomingCounter = 0;

        /**
         * Create new state class for a RPC sync conversation between two nodes.
         *
         * @param sender         endpoint for sending messages to remote endpoint asynchronously
         * @param selfId         id of current node
         * @param otherNodeId    id of the remote node
         * @param sleepAfterSync amount of time to sleep between sync attempts
         */
        public SyncConversation(
                @NonNull final GossipRpcSender sender,
                @NonNull final NodeId selfId,
                @NonNull final NodeId otherNodeId,
                @NonNull final Duration sleepAfterSync) {
            this.sender = sender;
            this.selfId = selfId;
            this.otherNodeId = otherNodeId;
            this.sleepAfterSync = Objects.requireNonNull(sleepAfterSync);
        }

        /**
         * Start synchronization with remote side, if all checks are successful (things like enough time has passed
         * since last synchronization, remote side has not fallen behind etc
         */
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

            if (this.mySyncData == null) {
                sendSyncData();
            } else {
                syncMetrics.setDoNotSyncAlreadyStarted();
            }
        }

        /**
         * Clean all resources and deregister itself
         */
        public void cleanup() {
            clearInternalState();
            GossipRpcShadowgraphSynchronizer.this.allConversations.remove(this);
        }

        /**
         * Send event to remote node outside of normal sync logic (most probably due to chatter)
         *
         * @param gossipEvent event to be sent
         */
        void broadcastEvent(@NonNull final GossipEvent gossipEvent) {
            // don't spam remote side if it is going to reconnect
            // or if we haven't completed even a first sync, as it might be a recovery phase for either for us
            if (!this.remoteFallenBehind && this.lastSyncTime != Instant.MIN) {
                sender.sendChatterEvent(gossipEvent);
            }
        }

        // HANDLE INCOMING MESSAGES

        /**
         * {@inheritDoc}
         */
        @Override
        public void receiveSyncData(@NonNull final SyncData syncMessage) {

            syncMetrics.acceptedSyncRequest();

            // if they are sending us sync data, they are no longer falling behind, nor sending events
            this.remoteFallenBehind = false;
            this.remoteStillSendingEvents = false;

            if (this.mySyncData == null) {
                sendSyncData();
            }

            this.remoteSyncData = syncMessage;

            syncMetrics.eventWindow(mySyncData.eventWindow(), remoteSyncData.eventWindow());

            final SyncFallenBehindStatus behindStatus =
                    fallenBehind(mySyncData.eventWindow(), remoteSyncData.eventWindow(), otherNodeId);
            if (behindStatus != SyncFallenBehindStatus.NONE_FALLEN_BEHIND) {
                logger.info(
                        LogMarker.RECONNECT.getMarker(),
                        "Sync falling behind self {} remote {} status {}",
                        mySyncData.eventWindow(),
                        remoteSyncData.eventWindow(),
                        behindStatus);
                clearInternalState();
                if (behindStatus == SyncFallenBehindStatus.OTHER_FALLEN_BEHIND) {
                    this.remoteFallenBehind = true;
                } else {
                    sender.breakConversation();
                }
                return;
            }

            sendKnownTips();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void receiveTips(@NonNull final List<Boolean> remoteTipKnowledge) {

            // Add each tip they know to the known set
            final List<ShadowEvent> knownTips = getMyTipsTheyKnow(otherNodeId, myTips, remoteTipKnowledge);
            eventsTheyHave.addAll(knownTips);

            // create a send list based on the known set
            final List<PlatformEvent> sendList =
                    createSendList(selfId, eventsTheyHave, mySyncData.eventWindow(), remoteSyncData.eventWindow());
            sender.sendEvents(
                    sendList.stream().map(PlatformEvent::getGossipEvent).collect(Collectors.toList()));
            sender.sendEndOfEvents().thenRun(() -> {
                //                logger.info(LogMarker.RECONNECT.getMarker(),
                //                        "Sync finished with {} sent {} events, my round {} their round {}, sync
                // incoming events {}, chatter incoming events {}", otherNodeId,
                //                        sendList.size(), mySyncData.eventWindow().latestConsensusRound(),
                //                        remoteSyncData.eventWindow().latestConsensusRound(), syncIncomingCounter,
                // chatterIncomingCounter);
                finishedSendingEvents();
            });
            syncMetrics.syncDone(new SyncResult(false, otherNodeId, 0, sendList.size()));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
            final long start = System.nanoTime();
            syncIncomingCounter += gossipEvents.size();
            gossipEvents.forEach(this::handleIncomingEvent);
            syncMetrics.eventsReceived(start, gossipEvents.size());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void receiveEventsFinished() {
            this.remoteStillSendingEvents = false;
        }

        @Override
        public void receiveChatterEvent(final GossipEvent gossipEvent) {
            chatterIncomingCounter++;
            final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
            eventHandler.accept(platformEvent);
        }

        // UTILITY METHODS

        private void sendSyncData() {
            this.shadowWindow = shadowGraph.reserve();
            this.myTips = getTips();
            final List<Hash> tipHashes =
                    myTips.stream().map(ShadowEvent::getEventBaseHash).collect(Collectors.toList());
            this.mySyncData = new SyncData(this.shadowWindow.getEventWindow(), tipHashes);
            sender.sendSyncData(this.mySyncData);
            syncMetrics.outgoingSyncRequestSent();
        }

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

        private void finishedSendingEvents() {
            clearInternalState();
        }

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
            this.syncIncomingCounter = 0;
            this.chatterIncomingCounter = 0;
        }

        /**
         * @return true if the cooldown period after a sync has elapsed, else false
         */
        private boolean isSyncCooldownComplete() {
            final Duration elapsed = Duration.between(lastSyncTime, time.now());
            return isGreaterThanOrEqualTo(elapsed, sleepAfterSync);
        }

        /**
         * Propagate single event down the intake pipeline
         *
         * @param gossipEvent event received from the remote peer
         */
        private void handleIncomingEvent(@NonNull final GossipEvent gossipEvent) {
            final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
            platformEvent.setSenderId(otherNodeId);
            intakeEventCounter.eventEnteredIntakePipeline(otherNodeId);
            eventHandler.accept(platformEvent);
        }
    }
}
