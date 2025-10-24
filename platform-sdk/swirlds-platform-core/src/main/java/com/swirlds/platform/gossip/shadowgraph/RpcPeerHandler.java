// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getMyTipsTheyKnow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getTheirTipsIHave;
import static org.hiero.base.CompareTo.isGreaterThanOrEqualTo;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.permits.SyncGuard;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiver;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
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
 * Conversation logic for an RPC exchange between two nodes. At this moment mostly concerned with performing a sync,
 * using {@link RpcShadowgraphSynchronizer}, but in the future, it can extend to handle more responsibilities. Most of
 * its internal state was externalized to {@link RpcPeerState} for clarity.
 */
public class RpcPeerHandler implements GossipRpcReceiver {

    private static final Logger logger = LogManager.getLogger(RpcPeerHandler.class);

    /**
     * Shared logic reference for actions which have to work against global state (mostly shadowgraph)
     */
    private final RpcShadowgraphSynchronizer sharedShadowgraphSynchronizer;

    /**
     * Metrics for sync related numbers
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
     * Node id of self node
     */
    private final NodeId selfId;

    /**
     * Endpoint for sending messages to peer endpoint asynchronously
     */
    private final GossipRpcSender sender;

    /**
     * Node id of the peer
     */
    private final NodeId peerId;

    /**
     * Amount of time to sleep between sync attempts
     */
    private final Duration sleepAfterSync;

    /**
     * Platform callback to be executed when protocol receives event from peer node
     */
    private final Consumer<PlatformEvent> eventHandler;

    /**
     * Internal state class, which offloads some complexity of managing it, but still is very much internal detail of
     * RpcPeerHandler
     */
    private final RpcPeerState state = new RpcPeerState();

    /**
     * Limiter to not spam with logs about falling behind compared to other nodes
     */
    private final RateLimiter fallBehindRateLimiter;

    /**
     * How many events were sent out to peer node during latest sync
     */
    private int outgoingEventsCounter = 0;

    /**
     * How many events were received from peer node during latest sync
     */
    private int incomingEventsCounter = 0;

    private final SyncGuard syncGuard;

    /**
     * Create new state class for an RPC peer
     *
     * @param sharedShadowgraphSynchronizer shared logic reference for actions which have to work against global state
     *                                      (mostly shadowgraph)
     * @param sender                        endpoint for sending messages to peer endpoint asynchronously
     * @param selfId                        id of current node
     * @param peerId                        id of the peer node
     * @param sleepAfterSync                amount of time to sleep between sync attempts
     * @param syncMetrics                   metrics for sync
     * @param time                          platform time
     * @param intakeEventCounter            used for tracking events in the intake pipeline per peer
     * @param eventHandler                  events that are received are passed here
     */
    public RpcPeerHandler(
            @NonNull final RpcShadowgraphSynchronizer sharedShadowgraphSynchronizer,
            @NonNull final GossipRpcSender sender,
            @NonNull final NodeId selfId,
            @NonNull final NodeId peerId,
            @NonNull final Duration sleepAfterSync,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Time time,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Consumer<PlatformEvent> eventHandler,
            @NonNull final SyncGuard syncGuard) {
        this.sharedShadowgraphSynchronizer = Objects.requireNonNull(sharedShadowgraphSynchronizer);
        this.sender = Objects.requireNonNull(sender);
        this.selfId = Objects.requireNonNull(selfId);
        this.peerId = Objects.requireNonNull(peerId);
        this.sleepAfterSync = Objects.requireNonNull(sleepAfterSync);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.time = Objects.requireNonNull(time);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.eventHandler = Objects.requireNonNull(eventHandler);
        this.syncGuard = syncGuard;
        this.fallBehindRateLimiter = new RateLimiter(time, Duration.ofMinutes(1));
    }

    /**
     * Start synchronization with remote side, if all checks are successful (things like enough time has passed since
     * last synchronization, remote side has not fallen behind etc
     *
     * @param wantToExit           set to true if for some external reasons we would like to exit sync loop
     * @param ignoreIncomingEvents we are in some kind of reduced capability state (for example caused by system being
     *                             unhealthy) and we shouldn't be processing/requesting incoming events
     * @return true if we should continue dispatching messages, false if we are in proper place to break rpc
     * conversation
     */
    // dispatch thread
    public boolean checkForPeriodicActions(final boolean wantToExit, final boolean ignoreIncomingEvents) {
        if (!isSyncCooldownComplete()) {
            this.syncMetrics.doNotSyncCooldown();
            return !wantToExit;
        }

        if (state.peerIsBehind) {
            this.syncMetrics.doNotSyncPeerFallenBehind();
            return !wantToExit;
        }

        if (state.peerStillSendingEvents) {
            this.syncMetrics.doNotSyncPeerProcessingEvents();
            return true;
        }

        if (this.intakeEventCounter.hasUnprocessedEvents(peerId)) {
            this.syncMetrics.doNotSyncIntakeCounter();
            return !wantToExit;
        }

        if (state.mySyncData == null) {
            if (!wantToExit) {
                if (state.remoteSyncData == null) {
                    if (!syncGuard.isSyncAllowed(peerId)) {
                        this.syncMetrics.doNotSyncFairSelector();
                        return true;
                    }
                } else {
                    // if remote side is starting sync with us, we want to do that, but still mark it as recently synced
                    syncGuard.onForcedSync(peerId);
                }
                // we have received remote sync request, so we want to reply, or sync selector told us it is our
                // time to initiate sync
                sendSyncData(ignoreIncomingEvents);
            }
            return !wantToExit;
        } else {
            this.syncMetrics.doNotSyncAlreadyStarted();
            return true;
        }
    }

    /**
     * Clean all resources and deregister itself
     */
    // protocol thread (which is equivalent to read-thread)
    public void cleanup() {
        clearInternalState();
        state.peerStillSendingEvents = false;
        sharedShadowgraphSynchronizer.deregisterPeerHandler(this);
        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.OUTSIDE_OF_RPC);
    }

    // HANDLE INCOMING MESSAGES - all done on dispatch thread

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveSyncData(@NonNull final SyncData syncMessage) {

        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.EXCHANGING_WINDOWS);
        this.syncMetrics.acceptedSyncRequest();

        state.syncInitiated(syncMessage);

        maybeBothSentSyncData();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveTips(@NonNull final List<Boolean> remoteTipKnowledge) {

        if (state.remoteSyncData == null) {
            throw new IllegalStateException("Need sync data before receiving tips from " + peerId);
        }

        // Add each tip they know to the known set
        final List<ShadowEvent> knownTips = getMyTipsTheyKnow(peerId, state.myTips, remoteTipKnowledge);

        state.eventsTheyHave.addAll(knownTips);
        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.EXCHANGING_EVENTS);

        if (!state.remoteSyncData.dontReceiveEvents()) {
            // create a send list based on the known set
            final List<PlatformEvent> sendList = sharedShadowgraphSynchronizer.createSendList(
                    selfId, state.eventsTheyHave, state.mySyncData.eventWindow(), state.remoteSyncData.eventWindow());
            sender.sendEvents(
                    sendList.stream().map(PlatformEvent::getGossipEvent).collect(Collectors.toList()));
            outgoingEventsCounter += sendList.size();
        }
        sender.sendEndOfEvents();
        finishedSendingEvents();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
        final SyncData mySyncData = state.mySyncData;
        if (mySyncData != null && mySyncData.dontReceiveEvents()) {
            // we ignore all incoming events - they should not be sent to us in first place
            logger.warn(
                    SYNC_INFO.getMarker(), "We have asked for no events, but still received an event from {}", peerId);
            return;
        }
        // this is one of two important parts of the code to keep outside critical section - receiving events
        final long start = time.nanoTime();
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
            this.syncMetrics.reportSyncPhase(peerId, SyncPhase.SENDING_EVENTS);
        }
        state.peerStillSendingEvents = false;
    }

    // UTILITY METHODS

    private void maybeBothSentSyncData() {

        if (state.mySyncData == null || state.remoteSyncData == null) {
            return;
        }
        final EventWindow remoteEventWindow = state.remoteSyncData.eventWindow();

        this.syncMetrics.eventWindow(state.mySyncData.eventWindow(), remoteEventWindow);

        this.sharedShadowgraphSynchronizer.reportRoundDifference(
                state.mySyncData.eventWindow(), remoteEventWindow, peerId);

        final SyncFallenBehindStatus behindStatus = sharedShadowgraphSynchronizer.hasFallenBehind(
                state.mySyncData.eventWindow(), state.remoteSyncData.eventWindow(), peerId);
        if (behindStatus != SyncFallenBehindStatus.NONE_FALLEN_BEHIND) {
            if (fallBehindRateLimiter.requestAndTrigger()) {
                logger.info(
                        LogMarker.RECONNECT.getMarker(),
                        "{} local ev={} remote ev={}",
                        behindStatus,
                        state.mySyncData.eventWindow(),
                        state.remoteSyncData.eventWindow());
            }
            clearInternalState();
            if (behindStatus == SyncFallenBehindStatus.OTHER_FALLEN_BEHIND) {
                this.syncMetrics.reportSyncPhase(peerId, SyncPhase.OTHER_FALLEN_BEHIND);
                state.peerIsBehind = true;
            } else {
                if (tryFixSelfFallBehind(remoteEventWindow)) {
                    this.syncMetrics.reportSyncPhase(peerId, SyncPhase.IDLE);
                    return;
                }
                this.syncMetrics.reportSyncPhase(peerId, SyncPhase.SELF_FALLEN_BEHIND);
                sender.breakConversation();
            }

            return;
        }

        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.EXCHANGING_TIPS);

        sendKnownTips();
    }

    private boolean tryFixSelfFallBehind(final EventWindow remoteEventWindow) {
        try (final ReservedEventWindow latestShadowWindow = sharedShadowgraphSynchronizer.reserveEventWindow()) {
            final SyncFallenBehindStatus behindStatus = sharedShadowgraphSynchronizer.hasFallenBehind(
                    latestShadowWindow.getEventWindow(), remoteEventWindow, peerId);
            if (behindStatus != SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
                // we seem to be ok after all, let's wait for another sync to happen
                return true;
            }

            return false;
        }
    }

    private void sendSyncData(final boolean ignoreIncomingEvents) {
        syncMetrics.syncStarted();
        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.EXCHANGING_WINDOWS);
        state.shadowWindow = sharedShadowgraphSynchronizer.reserveEventWindow();
        state.myTips = sharedShadowgraphSynchronizer.getTips();
        final List<Hash> tipHashes =
                state.myTips.stream().map(ShadowEvent::getEventBaseHash).collect(Collectors.toList());
        state.mySyncData = new SyncData(state.shadowWindow.getEventWindow(), tipHashes, ignoreIncomingEvents);
        sender.sendSyncData(state.mySyncData);
        this.syncMetrics.outgoingSyncRequestSent();

        maybeBothSentSyncData();
    }

    private void sendKnownTips() {

        // process the hashes received
        final List<ShadowEvent> theirTips = sharedShadowgraphSynchronizer.shadows(state.remoteSyncData.tipHashes());

        // For each tip they send us, determine if we have that event.
        // For each tip, send true if we have the event and false if we don't.
        final List<Boolean> theirTipsIHave = getTheirTipsIHave(theirTips);

        // Add their tips to the set of events they are known to have
        theirTips.stream().filter(Objects::nonNull).forEach(state.eventsTheyHave::add);

        state.peerStillSendingEvents = true;

        sender.sendTips(theirTipsIHave);
    }

    private void finishedSendingEvents() {
        if (!state.peerStillSendingEvents) {
            // have they already finished sending their events ? if yes, mark the sync as finished
            reportSyncFinished();
        } else {
            this.syncMetrics.reportSyncPhase(peerId, SyncPhase.RECEIVING_EVENTS);
        }
        clearInternalState();
    }

    private void reportSyncFinished() {
        this.syncMetrics.syncDone(new SyncResult(peerId, incomingEventsCounter, outgoingEventsCounter), null);
        incomingEventsCounter = 0;
        outgoingEventsCounter = 0;
        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.IDLE);
        syncMetrics.syncFinished();
    }

    /**
     * Marks state as finished for our side of the synchronization. It does NOT clear peerStillSendingEvents, this needs
     * to be cleared explicitly either on disconnect or when remote side tells us to
     */
    private void clearInternalState() {
        if (state.mySyncData != null) {
            syncGuard.onSyncCompleted(peerId);
        }
        state.clear(time.now());
    }

    /**
     * @return true if the cooldown period after a sync has elapsed, else false
     */
    private boolean isSyncCooldownComplete() {
        final Duration elapsed = Duration.between(state.lastSyncFinishedTime, this.time.now());
        return isGreaterThanOrEqualTo(elapsed, sleepAfterSync);
    }

    /**
     * Propagate single event down the intake pipeline
     *
     * @param gossipEvent event received from the remote peer
     */
    private void handleIncomingSyncEvent(@NonNull final GossipEvent gossipEvent) {
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
        platformEvent.setSenderId(peerId);
        this.intakeEventCounter.eventEnteredIntakePipeline(peerId);
        eventHandler.accept(platformEvent);
    }
}
