// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.filterLikelyDuplicates;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Common superclass for different types of shadowgraph synchronizers; it tries to abstract away network communication
 * specific parts and focus on handling retrieval of events which need to be synchronized between peers
 */
public class AbstractShadowgraphSynchronizer {

    private static final Logger logger = LogManager.getLogger();

    /**
     * The shadow graph manager to use for this sync
     */
    private final Shadowgraph shadowGraph;

    /**
     * Number of member nodes in the network for this sync
     */
    protected final int numberOfNodes;

    /**
     * All sync stats
     */
    protected final SyncMetrics syncMetrics;

    /**
     * consumes events received by the peer
     */
    protected final Consumer<PlatformEvent> eventHandler;

    /**
     * manages sync related decisions
     */
    private final FallenBehindManager fallenBehindManager;

    /**
     * Keeps track of how many events from each peer have been received, but haven't yet made it through the intake
     * pipeline
     */
    protected final IntakeEventCounter intakeEventCounter;

    /**
     * Platform time
     */
    protected final Time time;

    /**
     * If true then we do not send all events during a sync that the peer says we need. Instead, we send events that we
     * know are unlikely to be duplicates (e.g. self events), and only send other events if we have had them for a long
     * time and the peer still needs them.
     */
    private final boolean filterLikelyDuplicates;

    /**
     * For events that are neither self events nor ancestors of self events, we must have had this event for at least
     * this amount of time before it is eligible to be sent. Ignored if {@link #filterLikelyDuplicates} is false.
     */
    private final Duration nonAncestorFilterThreshold;

    /**
     * The maximum number of events to send in a single sync, or 0 if there is no limit.
     */
    protected final int maximumEventsPerSync;

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
     */
    public AbstractShadowgraphSynchronizer(
            @NonNull final PlatformContext platformContext,
            @NonNull final Shadowgraph shadowGraph,
            final int numberOfNodes,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final IntakeEventCounter intakeEventCounter) {
        Objects.requireNonNull(platformContext);

        this.time = platformContext.getTime();
        this.shadowGraph = Objects.requireNonNull(shadowGraph);
        this.numberOfNodes = numberOfNodes;
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        this.eventHandler = Objects.requireNonNull(receivedEventHandler);

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        this.nonAncestorFilterThreshold = syncConfig.nonAncestorFilterThreshold();

        this.filterLikelyDuplicates = syncConfig.filterLikelyDuplicates();
        this.maximumEventsPerSync = syncConfig.maxSyncEventCount();
    }

    @NonNull
    protected List<ShadowEvent> getTips() {
        final List<ShadowEvent> myTips = shadowGraph.getTips();
        syncMetrics.updateTipsPerSync(myTips.size());
        syncMetrics.updateMultiTipsPerSync(SyncUtils.computeMultiTipCount(myTips));
        return myTips;
    }

    /**
     * Decide if we have fallen behind with respect to this peer.
     *
     * @param self   our event window
     * @param other  their event window
     * @param nodeId node id against which we have fallen behind
     * @return status about who has fallen behind
     */
    protected SyncFallenBehindStatus hasFallenBehind(
            @NonNull final EventWindow self, @NonNull final EventWindow other, @NonNull final NodeId nodeId) {
        Objects.requireNonNull(self);
        Objects.requireNonNull(other);
        Objects.requireNonNull(nodeId);

        final SyncFallenBehindStatus status = SyncFallenBehindStatus.getStatus(self, other);
        if (status == SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
            fallenBehindManager.reportFallenBehind(nodeId);
        } else {
            fallenBehindManager.clearFallenBehind(nodeId);
        }

        if (status != SyncFallenBehindStatus.NONE_FALLEN_BEHIND) {
            logger.info(SYNC_INFO.getMarker(), "Connection against {} aborting sync due to {}", nodeId, status);
        }

        return status;
    }

    /**
     * Create a list of events to send to the peer.
     *
     * @param selfId           the id of this node
     * @param knownSet         the set of events that the peer already has (this is incomplete at this stage and is
     *                         added to during this method)
     * @param myEventWindow    the event window of this node
     * @param theirEventWindow the event window of the peer
     * @return a list of events to send to the peer
     */
    @NonNull
    protected List<PlatformEvent> createSendList(
            @NonNull final NodeId selfId,
            @NonNull final Set<ShadowEvent> knownSet,
            @NonNull final EventWindow myEventWindow,
            @NonNull final EventWindow theirEventWindow) {

        Objects.requireNonNull(selfId);
        Objects.requireNonNull(knownSet);
        Objects.requireNonNull(myEventWindow);
        Objects.requireNonNull(theirEventWindow);

        // add to knownSet all the ancestors of each known event
        final Set<ShadowEvent> knownAncestors = shadowGraph.findAncestors(
                knownSet, SyncUtils.unknownNonAncient(knownSet, myEventWindow, theirEventWindow));

        // since knownAncestors is a lot bigger than knownSet, it is a lot cheaper to add knownSet to knownAncestors
        // then vice versa
        knownAncestors.addAll(knownSet);

        syncMetrics.knownSetSize(knownAncestors.size());

        // predicate used to search for events to send
        final Predicate<ShadowEvent> knownAncestorsPredicate =
                SyncUtils.unknownNonAncient(knownAncestors, myEventWindow, theirEventWindow);

        // in order to get the peer the latest events, we get a new set of tips to search from
        final List<ShadowEvent> myNewTips = shadowGraph.getTips();

        // find all ancestors of tips that are not known
        final List<ShadowEvent> unknownTips =
                myNewTips.stream().filter(knownAncestorsPredicate).collect(Collectors.toList());
        final Set<ShadowEvent> sendSet = shadowGraph.findAncestors(unknownTips, knownAncestorsPredicate);
        // add the tips themselves
        sendSet.addAll(unknownTips);

        final List<PlatformEvent> eventsTheyMayNeed =
                sendSet.stream().map(ShadowEvent::getEvent).collect(Collectors.toCollection(ArrayList::new));

        SyncUtils.sort(eventsTheyMayNeed);

        List<PlatformEvent> sendList;
        if (filterLikelyDuplicates) {
            final long startFilterTime = time.nanoTime();
            sendList = filterLikelyDuplicates(selfId, nonAncestorFilterThreshold, time.now(), eventsTheyMayNeed);
            final long endFilterTime = time.nanoTime();
            syncMetrics.recordSyncFilterTime(endFilterTime - startFilterTime);
        } else {
            sendList = eventsTheyMayNeed;
        }

        if (maximumEventsPerSync > 0 && sendList.size() > maximumEventsPerSync) {
            sendList = sendList.subList(0, maximumEventsPerSync);
        }

        return sendList;
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    public void clear() {
        this.shadowGraph.clear();
    }

    /**
     * Events sent here should be gossiped to the network
     *
     * @param platformEvent event to be sent outside
     */
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        this.shadowGraph.addEvent(platformEvent);
    }

    /**
     * Updates the current event window (mostly ancient thresholds)
     *
     * @param eventWindow new event window to apply
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        this.shadowGraph.updateEventWindow(eventWindow);
    }

    /**
     * Starts helper threads needed for synchronizing shadowgraph
     */
    public void start() {}

    /**
     * Stops helper threads needed for synchronizing shadowgraph
     */
    public void stop() {}

    /**
     * {@link Shadowgraph#reserve()}
     */
    public ReservedEventWindow reserveEventWindow() {
        return shadowGraph.reserve();
    }

    /**
     * {@link Shadowgraph#shadows(List)} ()}
     */
    public List<ShadowEvent> shadows(final List<Hash> tips) {
        return shadowGraph.shadows(tips);
    }
}
