// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getTheirTipsIHave;
import static org.hiero.base.CompareTo.isGreaterThanOrEqualTo;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiver;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * The goal of the GossipRpcShadowgraphSynchronizer is to compare graphs with a remote node, and update them so both sides have
 * the same events in the graph. This process is called a sync.
 * <p>
 * This class is designed to use message based protocol (as a precursor to RPC communication) instead of stealing
 * entire socket (unlike {@link ShadowgraphSynchronizer}
 */
public class GossipRpcShadowgraphSynchronizer extends AbstractShadowgraphSynchronizer {

    private static final Logger logger = LogManager.getLogger(GossipRpcShadowgraphSynchronizer.class);

    private final List<SyncConversation> allConversations = new ArrayList<>();
    private final NodeId selfId;
    private final Duration syncPeriod;

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
        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        this.selfId = selfId;
        this.syncPeriod = syncConfig.syncPeriod();
    }

    public SyncConversation synchronize(
            @NonNull final GossipRpcSender sender, @NonNull final NodeId selfId, @NonNull final NodeId otherNodeId) {
        // TODO: Cleanup of conversations if they are recreated
        final SyncConversation conversation = new SyncConversation(sender, selfId, otherNodeId, syncPeriod);
        allConversations.add(conversation);
        return conversation;
    }

    @Override
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        super.addEvent(platformEvent);
        if (selfId.equals(platformEvent.getCreatorId())) {
            final GossipEvent gossipEvent = platformEvent.getGossipEvent();
            allConversations.forEach(conversation -> conversation.broadcastEvent(gossipEvent));
        }
    }

    public class SyncConversation implements GossipRpcReceiver {

        private final SyncTiming timing = new SyncTiming();
        private final Set<ShadowEvent> eventsTheyHave = new HashSet<>();
        private final GossipRpcSender sender;
        private final NodeId otherNodeId;
        private final NodeId selfId;
        private final Duration sleepAfterSync;

        private ReservedEventWindow shadowWindow;
        private SyncData mySyncData;
        private SyncData remoteSyncData;
        private List<ShadowEvent> myTips;
        private boolean remoteFallenBehind;
        private Instant lastSyncTime = Instant.MIN;
        private boolean remoteStillSendingEvents = false;

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

        @Override
        public void receiveSyncData(@NonNull final SyncData syncMessage) {
            // logger.info(SYNC_INFO.getMarker(), "{} received sync data ENTER", selfId);

            // if they are sending us sync data, they are no longer falling behind
            this.remoteFallenBehind = false;

            if (this.mySyncData == null) {
                sendSyncData();
            }
            timing.setTimePoint(1);

            this.remoteSyncData = syncMessage;

            syncMetrics.eventWindow(mySyncData.eventWindow(), remoteSyncData.eventWindow());

            if (fallenBehind(mySyncData.eventWindow(), remoteSyncData.eventWindow(), otherNodeId)) {
                // aborting the sync since someone has fallen behind
                handleFallenBehind();
                return;
            }

            sendKnownTips();
            // logger.info(SYNC_INFO.getMarker(), "{} received sync data EXIT", selfId);
        }

        @Override
        public void receiveFallenBehind() {
            clear();
            this.remoteStillSendingEvents = false;
            this.remoteFallenBehind = true;
        }

        @Override
        public void receiveTips(@NonNull final List<Boolean> remoteTipKnowledge) {
            // logger.info(SYNC_INFO.getMarker(), "{} received tips ENTER", selfId);
            timing.setTimePoint(3);

            // Add each tip they know to the known set
            final List<ShadowEvent> knownTips = getMyTipsTheyKnow(myTips, remoteTipKnowledge);
            eventsTheyHave.addAll(knownTips);

            // create a send list based on the known set
            final List<PlatformEvent> sendList =
                    createSendList(selfId, eventsTheyHave, mySyncData.eventWindow(), remoteSyncData.eventWindow());
            timing.setTimePoint(4);
            // logger.info(SYNC_INFO.getMarker(), "Sending {} events", sendList.size());
            sender.sendEvents(
                    sendList.stream().map(PlatformEvent::getGossipEvent).collect(Collectors.toList()));
            sender.sendEndOfEvents().thenRun(this::finishedSendingEvents);

            //            syncMetrics.syncDone(
            //                    new SyncResult(connection.isOutbound(), connection.getOtherId(), eventsRead,
            // sendList.size()));

            timing.setTimePoint(5);
            // syncMetrics.recordSyncTiming(timing, connection);

            // logger.info(SYNC_INFO.getMarker(), "{} received tips EXIT", selfId);
        }

        @Override
        public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
            gossipEvents.forEach(gossipEvent -> {
                final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
                platformEvent.setSenderId(otherNodeId);
                intakeEventCounter.eventEnteredIntakePipeline(otherNodeId);
                eventHandler.accept(platformEvent);
            });
        }

        @Override
        public void receiveEventsFinished() {
            this.remoteStillSendingEvents = false;
        }

        public void possiblyStartSync() {
            if (!syncCooldownComplete() || remoteFallenBehind || remoteStillSendingEvents) {
                return;
            }
            if (this.mySyncData == null) {
                sendSyncData();
            }
            timing.setTimePoint(1);
        }

        private void sendSyncData() {
            // logger.info(SYNC_INFO.getMarker(), "Sending sync data");
            this.shadowWindow = shadowGraph.reserve();
            timing.start();
            this.myTips = getTips();
            final List<Hash> tipHashes =
                    myTips.stream().map(ShadowEvent::getEventBaseHash).collect(Collectors.toList());
            this.mySyncData = new SyncData(this.shadowWindow.getEventWindow(), tipHashes);
            sender.sendSyncData(this.mySyncData);
            // logger.info(SYNC_INFO.getMarker(), "Sent sync data");
        }

        private void sendKnownTips() {

            // process the hashes received
            final List<ShadowEvent> theirTips = shadowGraph.shadows(remoteSyncData.tipHashes());

            // For each tip they send us, determine if we have that event.
            // For each tip, send true if we have the event and false if we don't.
            final List<Boolean> theirTipsIHave = getTheirTipsIHave(theirTips);

            // Add their tips to the set of events they are known to have
            theirTips.stream().filter(Objects::nonNull).forEach(eventsTheyHave::add);

            timing.setTimePoint(2);
            remoteStillSendingEvents = true;

            sender.sendTips(theirTipsIHave);
        }

        private void handleFallenBehind() {
            clear();
            sender.sendFallenBehind();
        }

        private void finishedSendingEvents() {
            clear();
        }

        private void clear() {
            // logger.info(SYNC_INFO.getMarker(), "{} Clearing shadow graph", selfId);
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

        @NonNull
        private List<ShadowEvent> getMyTipsTheyKnow(
                @NonNull final List<ShadowEvent> myTips, @NonNull final List<Boolean> myTipsTheyHave) {

            if (myTipsTheyHave.size() != myTips.size()) {
                throw new RuntimeException(String.format(
                        "during sync with %s, peer booleans list is wrong size. Expected: %d Actual: %d,",
                        otherNodeId, myTips.size(), myTipsTheyHave.size()));
            }
            final List<ShadowEvent> knownTips = new ArrayList<>();
            // process their booleans
            for (int i = 0; i < myTipsTheyHave.size(); i++) {
                if (Boolean.TRUE.equals(myTipsTheyHave.get(i))) {
                    knownTips.add(myTips.get(i));
                }
            }

            return knownTips;
        }

        /**
         * @return true if the cooldown period after a sync has elapsed, else false
         */
        private boolean syncCooldownComplete() {
            final Duration elapsed = Duration.between(lastSyncTime, time.now());

            return isGreaterThanOrEqualTo(elapsed, sleepAfterSync);
        }

        public void broadcastEvent(@NonNull final GossipEvent gossipEvent) {
            // logger.info(SYNC_INFO.getMarker(), "Broadcasting event from creator {} as {}",
            //        gossipEvent.eventCore().creatorNodeId(), selfId);
            sender.sendEvents(Collections.singletonList(gossipEvent));
        }
    }
}
