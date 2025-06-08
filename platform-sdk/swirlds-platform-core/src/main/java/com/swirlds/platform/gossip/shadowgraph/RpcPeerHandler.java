// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getMyTipsTheyKnow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getTheirTipsIHave;
import static org.hiero.base.CompareTo.isGreaterThanOrEqualTo;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiver;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * State class for an RPC exchange between two nodes.
 */
public class RpcPeerHandler implements GossipRpcReceiver {

    private static final Logger logger = LogManager.getLogger(RpcPeerHandler.class);

    /**
     * Shared logic reference for actions which have to work against global state (mostly shadowgraph)
     */
    private final GossipRpcShadowgraphSynchronizer sharedShadowgraph;

    /**
     * Materics for sync related numbers
     */
    private final SyncMetrics syncMetrics;
    /**
     * Platform time
     */
    private final Time time;

    /**
     * Used for tracking events in the intake pipeline per peer
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * Node id of current node
     */
    private final NodeId selfId;

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
     * Platform callback to be executed when protocol receives event from remote node
     */
    private final Consumer<PlatformEvent> eventHandler;

    /**
     * Internal state class, which offloads some complexity of managing it, but still is very much internal detail of
     * RpcPeerHandler
     */
    private final RpcPeerState state = new RpcPeerState();

    /**
     * How many events were sent out to remote node during latest sync
     */
    private int outgoingEventsCounter = 0;

    /**
     * How many events were received from remote node during latest sync
     */
    private int incomingEventsCounter = 0;

    /**
     * Create new state class for a RPC peer
     *
     * @param sharedShadowgraph  shared logic reference for actions which have to work against global state (mostly
     *                           shadowgraph)
     * @param sender             endpoint for sending messages to remote endpoint asynchronously
     * @param selfId             id of current node
     * @param otherNodeId        id of the remote node
     * @param sleepAfterSync     amount of time to sleep between sync attempts
     * @param syncMetrics        metrics for sync
     * @param time               platform time
     * @param intakeEventCounter used for tracking events in the intake pipeline per peer
     * @param eventHandler       events that are received are passed here
     */
    public RpcPeerHandler(
            @NonNull final GossipRpcShadowgraphSynchronizer sharedShadowgraph,
            @NonNull final GossipRpcSender sender,
            @NonNull final NodeId selfId,
            @NonNull final NodeId otherNodeId,
            @NonNull final Duration sleepAfterSync,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Time time,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Consumer<PlatformEvent> eventHandler) {
        this.sharedShadowgraph = Objects.requireNonNull(sharedShadowgraph);
        this.sender = Objects.requireNonNull(sender);
        this.selfId = Objects.requireNonNull(selfId);
        this.otherNodeId = Objects.requireNonNull(otherNodeId);
        this.sleepAfterSync = Objects.requireNonNull(sleepAfterSync);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.time = Objects.requireNonNull(time);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.eventHandler = Objects.requireNonNull(eventHandler);
    }

    /**
     * Start synchronization with remote side, if all checks are successful (things like enough time has passed since
     * last synchronization, remote side has not fallen behind etc
     */
    // dispatch thread
    public void checkForPeriodicActions() {
        if (!isSyncCooldownComplete()) {
            this.syncMetrics.doNotSyncCooldown();
            return;
        }

        if (state.remoteFallenBehind) {
            this.syncMetrics.doNotSyncRemoteFallenBehind();
            return;
        }

        if (state.remoteStillSendingEvents) {
            this.syncMetrics.setDoNotSyncRemoteProcessingEvents();
            return;
        }

        if (this.intakeEventCounter.hasUnprocessedEvents(otherNodeId)) {
            this.syncMetrics.doNotSyncIntakeCounter();
            return;
        }

        if (state.mySyncData == null) {
            sendSyncData();
        } else {
            this.syncMetrics.setDoNotSyncAlreadyStarted();
        }
    }

    /**
     * Clean all resources and deregister itself
     */
    // protocol thread (which is equivalent to read-thread)
    public void cleanup() {
        clearInternalState();
        sharedShadowgraph.deregisterPeerHandler(this);
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

        // be careful - this is unsynchronized access to non-volatile variables; given it is only a hint, we don't
        // really care if it is immediately visible with updates
        if (!state.remoteFallenBehind && state.lastSyncTime != Instant.MIN) {
            sender.sendBroadcastEvent(gossipEvent);
        }
    }

    // HANDLE INCOMING MESSAGES - all done on dispatch thread

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveSyncData(@NonNull final SyncData syncMessage) {

        this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_WINDOWS);
        this.syncMetrics.acceptedSyncRequest();

        // if they are sending us sync data, they are no longer falling behind, nor sending events
        state.remoteFallenBehind = false;
        state.remoteStillSendingEvents = false;

        state.remoteSyncData = syncMessage;

        maybeBothSentSyncData();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveTips(@NonNull final List<Boolean> remoteTipKnowledge) {

        // Add each tip they know to the known set
        final List<ShadowEvent> knownTips = getMyTipsTheyKnow(otherNodeId, state.myTips, remoteTipKnowledge);

        state.eventsTheyHave.addAll(knownTips);
        this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_EVENTS);

        // create a send list based on the known set
        final List<PlatformEvent> sendList = sharedShadowgraph.createSendList(
                selfId, state.eventsTheyHave, state.mySyncData.eventWindow(), state.remoteSyncData.eventWindow());
        sender.sendEvents(sendList.stream().map(PlatformEvent::getGossipEvent).collect(Collectors.toList()));
        outgoingEventsCounter += sendList.size();
        sender.sendEndOfEvents().thenRun(this::finishedSendingEvents);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
        // this is one of two important parts of the code to keep outside critical section - receiving events
        final long start = System.nanoTime();
        incomingEventsCounter += gossipEvents.size();
        gossipEvents.forEach(this::handleIncomingSyncEvent);
        this.syncMetrics.eventsReceived(start, gossipEvents.size());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveEventsFinished() {
        if (state.mySyncData == null) {
            // have we already finished sending out events? if yes, mark the sync as finished
            reportSyncFinished();
        } else {
            this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.SENDING_EVENTS);
        }
        state.remoteStillSendingEvents = false;
    }

    @Override
    public void receiveBroadcastEvent(final GossipEvent gossipEvent) {
        // we don't use handleIncomingSyncEvent, as we don't want to block sync till this event is resolved
        // so no marking it in intakeEventCounter
        this.syncMetrics.broadcastEventReceived();
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
        eventHandler.accept(platformEvent);
    }

    // UTILITY METHODS

    private void maybeBothSentSyncData() {

        if (state.mySyncData == null || state.remoteSyncData == null) {
            return;
        }
        final EventWindow remoteEventWindow = state.remoteSyncData.eventWindow();

        this.syncMetrics.eventWindow(state.mySyncData.eventWindow(), remoteEventWindow);

        final SyncFallenBehindStatus behindStatus = sharedShadowgraph.hasFallenBehind(
                state.mySyncData.eventWindow(), state.remoteSyncData.eventWindow(), otherNodeId);
        if (behindStatus != SyncFallenBehindStatus.NONE_FALLEN_BEHIND) {
            logger.info(
                    LogMarker.RECONNECT.getMarker(),
                    "{} local ev={} remote ev={}",
                    behindStatus,
                    state.mySyncData.eventWindow(),
                    state.remoteSyncData.eventWindow());

            clearInternalState();
            if (behindStatus == SyncFallenBehindStatus.OTHER_FALLEN_BEHIND) {
                this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.OTHER_FALLEN_BEHIND);
                state.remoteFallenBehind = true;
            } else {
                if (tryFixSelfFallBehind(remoteEventWindow)) {
                    this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.IDLE);
                    return;
                }
                this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.SELF_FALLEN_BEHIND);
                sender.breakConversation();
            }

            return;
        }

        this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_TIPS);

        sendKnownTips();
    }

    private boolean tryFixSelfFallBehind(final EventWindow remoteEventWindow) {
        final ReservedEventWindow latestShadowWindow = sharedShadowgraph.reserveEventWindow();
        try {
            final SyncFallenBehindStatus behindStatus = sharedShadowgraph.hasFallenBehind(
                    latestShadowWindow.getEventWindow(), remoteEventWindow, otherNodeId);
            if (behindStatus != SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
                // we seem to be ok after all, let's wait for another sync to happen
                logger.info(
                        LogMarker.RECONNECT.getMarker(),
                        "Latest event window is not really falling behind, will retry sync local ev={} remote ev={}",
                        latestShadowWindow.getEventWindow(),
                        remoteEventWindow);
                return true;
            }

            return false;
        } finally {
            latestShadowWindow.close();
        }
    }

    private void sendSyncData() {
        this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.EXCHANGING_WINDOWS);
        state.shadowWindow = sharedShadowgraph.reserveEventWindow();
        state.myTips = sharedShadowgraph.getTips();
        final List<Hash> tipHashes =
                state.myTips.stream().map(ShadowEvent::getEventBaseHash).collect(Collectors.toList());
        state.mySyncData = new SyncData(state.shadowWindow.getEventWindow(), tipHashes);
        sender.sendSyncData(state.mySyncData);
        this.syncMetrics.outgoingSyncRequestSent();

        maybeBothSentSyncData();
    }

    private void sendKnownTips() {

        // process the hashes received
        final List<ShadowEvent> theirTips = sharedShadowgraph.shadows(state.remoteSyncData.tipHashes());

        // For each tip they send us, determine if we have that event.
        // For each tip, send true if we have the event and false if we don't.
        final List<Boolean> theirTipsIHave = getTheirTipsIHave(theirTips);

        // Add their tips to the set of events they are known to have
        theirTips.stream().filter(Objects::nonNull).forEach(state.eventsTheyHave::add);

        state.remoteStillSendingEvents = true;

        sender.sendTips(theirTipsIHave);
    }

    private void finishedSendingEvents() {
        if (!state.remoteStillSendingEvents) {
            // have they already finished sending their events ? if yes, mark the sync as finished
            reportSyncFinished();
        } else {
            this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.RECEIVING_EVENTS);
        }
        clearInternalState();
    }

    private void reportSyncFinished() {
        this.syncMetrics.syncDone(new SyncResult(false, otherNodeId, incomingEventsCounter, outgoingEventsCounter));
        incomingEventsCounter = 0;
        outgoingEventsCounter = 0;
        this.syncMetrics.reportSyncPhase(otherNodeId, SyncPhase.IDLE);
    }

    private void clearInternalState() {
        state.clear(time.now());
    }

    /**
     * @return true if the cooldown period after a sync has elapsed, else false
     */
    private boolean isSyncCooldownComplete() {
        final Duration elapsed = Duration.between(state.lastSyncTime, this.time.now());
        return isGreaterThanOrEqualTo(elapsed, sleepAfterSync);
    }

    /**
     * Propagate single event down the intake pipeline
     *
     * @param gossipEvent event received from the remote peer
     */
    private void handleIncomingSyncEvent(@NonNull final GossipEvent gossipEvent) {
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
        platformEvent.setSenderId(otherNodeId);
        this.intakeEventCounter.eventEnteredIntakePipeline(otherNodeId);
        eventHandler.accept(platformEvent);
    }
}
