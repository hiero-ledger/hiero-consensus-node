// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;
import static org.hiero.base.CompareTo.isGreaterThanOrEqualTo;
import static org.hiero.consensus.gossip.impl.gossip.shadowgraph.SyncUtils.getMyTipsTheyKnow;
import static org.hiero.consensus.gossip.impl.gossip.shadowgraph.SyncUtils.getTheirTipsIHave;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import com.swirlds.logging.legacy.LogMarker;
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
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncGuard;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcReceiverHandler;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcSender;
import org.hiero.consensus.gossip.impl.gossip.rpc.SyncData;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.monitoring.FallenBehindStatus;

/**
 * Conversation logic for an RPC exchange between two nodes. At this moment mostly concerned with performing a sync,
 * using {@link ShadowgraphSynchronizer}, but in the future, it can extend to handle more responsibilities. Most of its
 * internal state was externalized to {@link RpcPeerState} for clarity.
 */
public class RpcPeerHandler implements GossipRpcReceiverHandler {

    private static final Logger logger = LogManager.getLogger(RpcPeerHandler.class);

    /**
     * Shared logic reference for actions which have to work against global state (mostly shadowgraph)
     */
    private final ShadowgraphSynchronizer sharedShadowgraphSynchronizer;

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

    private final SyncConfig syncConfig;
    private final BroadcastConfig broadcastConfig;

    /**
     * How many events were sent out to peer node during latest sync
     */
    private int outgoingEventsCounter = 0;

    /**
     * How many events were received from peer node during latest sync
     */
    private int incomingEventsCounter = 0;

    /**
     * Last time we have finished receiving events from a full sync
     */
    private long lastReceiveEventFinished;

    private final SyncGuard syncGuard;

    /**
     * Keeps track of the FallenBehind status of the local node
     */
    private final FallenBehindMonitor fallenBehindMonitor;

    /**
     * Should all incoming events be ignored due to platform being unhealthy
     */
    private boolean ignoreIncomingEvents;

    /**
     * Indication if communication with peer is overloaded for some reason (network issues). Used to disable broadcast
     * in such periods. Volatile, as it can be set from various threads and read from dispatch thread later.
     */
    private volatile boolean communicationOverload = false;

    /**
     * Create new state class for an RPC peer
     *
     * @param sharedShadowgraphSynchronizer shared logic reference for actions which have to work against global state
     *                                      (mostly shadowgraph)
     * @param sender                        endpoint for sending messages to peer endpoint asynchronously
     * @param selfId                        id of current node
     * @param peerId                        id of the peer node
     * @param syncMetrics                   metrics for sync
     * @param time                          platform time
     * @param intakeEventCounter            used for tracking events in the intake pipeline per peer
     * @param eventHandler                  events that are received are passed here
     * @param fallenBehindMonitor           an instance of the fallenBehind Monitor which tracks if the node has fallen
     *                                      behind
     * @param syncConfig                    sync configuration
     * @param broadcastConfig               broadcast configuration
     */
    public RpcPeerHandler(
            @NonNull final ShadowgraphSynchronizer sharedShadowgraphSynchronizer,
            @NonNull final GossipRpcSender sender,
            @NonNull final NodeId selfId,
            @NonNull final NodeId peerId,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Time time,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Consumer<PlatformEvent> eventHandler,
            @NonNull final SyncGuard syncGuard,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final SyncConfig syncConfig,
            @NonNull final BroadcastConfig broadcastConfig) {
        this.sharedShadowgraphSynchronizer = Objects.requireNonNull(sharedShadowgraphSynchronizer);
        this.sender = Objects.requireNonNull(sender);
        this.selfId = Objects.requireNonNull(selfId);
        this.peerId = Objects.requireNonNull(peerId);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.time = Objects.requireNonNull(time);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.eventHandler = Objects.requireNonNull(eventHandler);
        this.syncGuard = syncGuard;
        this.fallenBehindMonitor = fallenBehindMonitor;
        this.fallBehindRateLimiter = new RateLimiter(time, Duration.ofMinutes(1));
        this.lastReceiveEventFinished = time.nanoTime();
        this.syncConfig = Objects.requireNonNull(syncConfig);
        this.broadcastConfig = Objects.requireNonNull(broadcastConfig);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Called on dispatch thread
     */
    @Override
    public boolean checkForPeriodicActions(final boolean wantToExit, final boolean ignoreIncomingEvents) {

        this.ignoreIncomingEvents = ignoreIncomingEvents;

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
     * {@inheritDoc}
     * <p>
     * Called on protocol thread (which is equivalent to read-thread)
     */
    @Override
    public void cleanup() {
        if (state.mySyncData != null) {
            // it might be partial sync, but we still need to mark it as finished for metrics to work correctly
            reportSyncFinished();
        }
        clearInternalState();
        state.peerStillSendingEvents = false;
        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.OUTSIDE_OF_RPC);
        // mark sync as never happened to stop broadcast from running
        state.lastSyncFinishedTime = Instant.MIN;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCommunicationOverloaded(final boolean overloaded) {
        communicationOverload = overloaded;
    }

    /**
     * Send event to remote node outside of normal sync logic (due to broadcast)
     * <p>
     * Called on protocol thread (which is equivalent to read-thread)
     *
     * @param gossipEvent event to be sent
     */
    public void broadcastEvent(@NonNull final GossipEvent gossipEvent) {
        // don't spam remote side if it is going to reconnect
        // or if we haven't completed even a first sync, as it might be a recovery phase for either for us

        // be careful - this is unsynchronized access to non-volatile variables; given it is only a hint, we don't
        // really care if it is immediately visible with updates
        if (isBroadcastRunning()) {
            sender.sendBroadcastEvent(gossipEvent);
        }
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

        if (state.mySyncData == null) {
            throw new IllegalStateException("Received tips confirmation before sending sync data from " + peerId);
        }

        if (state.myTips == null) {
            throw new IllegalStateException(
                    "Internal inconsistency - sent sync data but no info about my tips, when receiving tips from "
                            + peerId);
        }

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
                    selfId,
                    state.eventsTheyHave,
                    state.mySyncData.eventWindow(),
                    state.remoteSyncData.eventWindow(),
                    isBroadcastRunning());
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

        incomingEventsCounter += gossipEvents.size();
        gossipEvents.forEach(this::handleIncomingSyncEvent);
        this.syncMetrics.eventsReceived(lastReceiveEventFinished, gossipEvents.size());
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
        this.lastReceiveEventFinished = time.nanoTime();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveBroadcastEvent(@NonNull final GossipEvent gossipEvent) {
        // we don't use handleIncomingSyncEvent, as we don't want to block sync till this event is resolved
        // so no marking it in intakeEventCounter

        if (ignoreIncomingEvents) {
            // we need to ignore broadcast events if system is unhealthy
            return;
        }

        // this method won't be called if we have fallen behind, as reconnect protocol will take over, preempting rpc
        // protocol, so nobody will broadcast events to us anymore; this means we won't be overloading intake pipeline
        // with random events, no need to make extra checks here
        this.syncMetrics.broadcastEventReceived();
        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
        platformEvent.setSenderId(peerId);
        this.intakeEventCounter.eventEnteredIntakePipeline(peerId);

        eventHandler.accept(platformEvent);
    }

    // UTILITY METHODS

    private void maybeBothSentSyncData() {

        if (state.mySyncData == null || state.remoteSyncData == null) {
            return;
        }
        final EventWindow remoteEventWindow = state.remoteSyncData.eventWindow();

        this.syncMetrics.eventWindow(state.mySyncData.eventWindow(), remoteEventWindow);

        this.sharedShadowgraphSynchronizer.reportSyncStatus(state.mySyncData.eventWindow(), remoteEventWindow, peerId);

        final FallenBehindStatus behindStatus =
                fallenBehindMonitor.check(state.mySyncData.eventWindow(), state.remoteSyncData.eventWindow(), peerId);
        if (behindStatus != FallenBehindStatus.NONE_FALLEN_BEHIND) {
            if (fallBehindRateLimiter.requestAndTrigger()) {
                logger.info(
                        LogMarker.RECONNECT.getMarker(),
                        "{} local ev={} remote ev={}",
                        behindStatus,
                        state.mySyncData.eventWindow(),
                        state.remoteSyncData.eventWindow());
            }
            clearInternalState();
            if (behindStatus == FallenBehindStatus.OTHER_FALLEN_BEHIND) {
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

    private boolean tryFixSelfFallBehind(@NonNull final EventWindow remoteEventWindow) {
        try (final ReservedEventWindow latestShadowWindow = sharedShadowgraphSynchronizer.reserveEventWindow()) {
            final FallenBehindStatus behindStatus =
                    fallenBehindMonitor.check(latestShadowWindow.getEventWindow(), remoteEventWindow, peerId);
            if (behindStatus != FallenBehindStatus.SELF_FALLEN_BEHIND) {
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
                state.myTips.stream().map(ShadowEvent::getBaseHash).collect(Collectors.toList());
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
        this.syncMetrics.syncFinished();
        this.syncMetrics.reportSyncPhase(peerId, SyncPhase.IDLE);
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
        return isGreaterThanOrEqualTo(
                elapsed,
                isBroadcastRunning()
                        ? broadcastConfig.rpcSleepAfterSyncWhileBroadcasting()
                        : syncConfig.rpcSleepAfterSync());
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

    /**
     * Are we currently in the state where broadcast is allowed to run?
     * <p>
     * Can be called on various threads. It is informative only, so we won't break if it is slightly delayed in
     * reporting the status.
     *
     * @return should broadcast be active
     */
    private boolean isBroadcastRunning() {

        return broadcastConfig.enableBroadcast()
                && !state.peerIsBehind
                && state.lastSyncFinishedTime != Instant.MIN
                && !communicationOverload;
    }
}
