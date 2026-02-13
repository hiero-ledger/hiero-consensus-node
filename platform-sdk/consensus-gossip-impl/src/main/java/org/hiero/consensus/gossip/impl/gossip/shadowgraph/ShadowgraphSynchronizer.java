// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.gossip.impl.gossip.shadowgraph.SyncUtils.filterLikelyDuplicates;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * The goal of the GossipRpcShadowgraphSynchronizer is to compare graphs with a remote node, and update them so both
 * sides have the same events in the graph. This process is called a sync.
 * <p>
 * This class is designed to use message based protocol (as a precursor to RPC communication)
 */
public class ShadowgraphSynchronizer {

    /**
     * The shadow graph manager to use for this sync
     */
    private final Shadowgraph shadowGraph;

    /**
     * All sync stats
     */
    protected final SyncMetrics syncMetrics;

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
     * For events that are ancestors of self events, we must have had this event for at least this amount
     * of time before it is eligible to be sent. Ignored if {@link #filterLikelyDuplicates} is false. It helps to reduce
     * the duplicate ratio when using broadcast. Not active if broadcast is disabled.
     */
    private final Duration ancestorFilterThreshold;

    /**
     * For events that are self events, we must have had this event for at least this amount
     * of time before it is eligible to be sent. Ignored if {@link #filterLikelyDuplicates} is false. It helps to reduce
     * the duplicate ratio when using broadcast. Not active if broadcast is disabled.
     */
    private final Duration selfFilterThreshold;

    /**
     * The maximum number of events to send in a single sync, or 0 if there is no limit.
     */
    protected final int maximumEventsPerSync;

    private final Consumer<SyncProgress> syncProgressHandler;

    /**
     * Constructs a new ShadowgraphSynchronizer.
     *
     * @param configuration the platform configuration
     * @param metrics the metrics system
     * @param time source of time
     * @param numberOfNodes number of nodes in the network
     * @param syncMetrics metrics for sync
     * @param intakeEventCounter used for tracking events in the intake pipeline per peer
     * @param syncLagHandler callback for reporting median sync lag
     */
    public ShadowgraphSynchronizer(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            final int numberOfNodes,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Consumer<SyncProgress> syncLagHandler) {

        this.time = requireNonNull(time);
        this.shadowGraph = new Shadowgraph(metrics, numberOfNodes, intakeEventCounter);
        this.syncMetrics = requireNonNull(syncMetrics);

        final SyncConfig syncConfig = configuration.getConfigData(SyncConfig.class);
        this.nonAncestorFilterThreshold = syncConfig.nonAncestorFilterThreshold();
        this.ancestorFilterThreshold = syncConfig.ancestorFilterThreshold();
        this.selfFilterThreshold = syncConfig.selfFilterThreshold();

        this.filterLikelyDuplicates = syncConfig.filterLikelyDuplicates();
        this.maximumEventsPerSync = syncConfig.maxSyncEventCount();

        this.syncProgressHandler = syncLagHandler;
    }

    @NonNull
    protected List<ShadowEvent> getTips() {
        final List<ShadowEvent> myTips = shadowGraph.getTips();
        syncMetrics.updateTipsPerSync(myTips.size());
        syncMetrics.updateMultiTipsPerSync(SyncUtils.computeMultiTipCount(myTips));
        return myTips;
    }

    protected void reportSyncStatus(
            @NonNull final EventWindow self, @NonNull final EventWindow other, @NonNull final NodeId nodeId) {
        syncProgressHandler.accept(new SyncProgress(nodeId, self, other));
    }

    /**
     * Create a list of events to send to the peer.
     *
     * @param selfId           the id of this node
     * @param knownSet         the set of events that the peer already has (this is incomplete at this stage and is
     *                         added to during this method)
     * @param myEventWindow    the event window of this node
     * @param theirEventWindow the event window of the peer
     * @param broadcastRunning is broadcast running currently, which would suggest delaying syncing self events
     * @return a list of events to send to the peer
     */
    @NonNull
    protected List<PlatformEvent> createSendList(
            @NonNull final NodeId selfId,
            @NonNull final Set<ShadowEvent> knownSet,
            @NonNull final EventWindow myEventWindow,
            @NonNull final EventWindow theirEventWindow,
            final boolean broadcastRunning) {

        requireNonNull(selfId);
        requireNonNull(knownSet);
        requireNonNull(myEventWindow);
        requireNonNull(theirEventWindow);

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
                sendSet.stream().map(ShadowEvent::getPlatformEvent).collect(Collectors.toCollection(ArrayList::new));

        SyncUtils.sort(eventsTheyMayNeed);

        List<PlatformEvent> sendList;
        if (filterLikelyDuplicates) {
            final long startFilterTime = time.nanoTime();
            sendList = filterLikelyDuplicates(
                    selfId,
                    nonAncestorFilterThreshold,
                    broadcastRunning ? ancestorFilterThreshold : Duration.ZERO,
                    broadcastRunning ? selfFilterThreshold : Duration.ZERO,
                    time.now(),
                    eventsTheyMayNeed);
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
