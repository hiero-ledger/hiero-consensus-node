// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.creation.tipset;

import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.event.creation.tipset.TipsetAdvancementWeight.ZERO_ADVANCEMENT_WEIGHT;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.platform.roster.RosterUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.event.creator.impl.EventCreationConfig;

/**
 * Calculates tipset advancement weights for events created by a node.
 */
public class TipsetWeightCalculator {

    private static final Logger logger = LogManager.getLogger(TipsetWeightCalculator.class);

    /**
     * The node ID that is being tracked by this object.
     */
    private final NodeId selfId;

    /**
     * Builds tipsets for each event. Is maintained outside this object.
     */
    private final TipsetTracker tipsetTracker;

    /**
     * Tracks non-ancient events without children.
     */
    private final ChildlessEventTracker childlessEventTracker;

    /**
     * The current tipset snapshot. This is updated to the latest self event's tipset whenever the weighted advancement
     * between the current snapshot and the new event's tipset exceeds the threshold of 2/3 consensus weight minus the
     * self weight.
     */
    private Tipset snapshot;

    /**
     * The N most recent snapshots.
     */
    private final Deque<Tipset> snapshotHistory = new LinkedList<>();

    /**
     * The number of snapshots to keep in {@link #snapshotHistory}.
     */
    private final int maxSnapshotHistorySize;

    /**
     * The total weight of all nodes.
     */
    private final long totalWeight;

    /**
     * The weight of the node tracked by this object.
     */
    private final long selfWeight;

    /**
     * The maximum possible advancement weight for an event.
     */
    private final long maximumPossibleAdvancementWeight;

    /**
     * The previous tipset advancement weight.
     */
    private TipsetAdvancementWeight previousAdvancementWeight = ZERO_ADVANCEMENT_WEIGHT;

    /**
     * The tipset of the latest self event, or the starting snapshot if there has not yet been a self event.
     */
    private Tipset latestSelfEventTipset;

    private final Roster roster;

    private final RateLimitedLogger ancientParentLogger;
    private final RateLimitedLogger allParentsAreAncientLogger;

    /**
     * Create a new tipset weight calculator.
     *
     * @param platformContext       the platform context
     * @param roster                the current roster
     * @param selfId                the ID of the node tracked by this object
     * @param tipsetTracker         builds tipsets for individual events
     * @param childlessEventTracker tracks non-ancient events without children
     */
    public TipsetWeightCalculator(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final TipsetTracker tipsetTracker,
            @NonNull final ChildlessEventTracker childlessEventTracker) {

        this.selfId = Objects.requireNonNull(selfId);
        this.tipsetTracker = Objects.requireNonNull(tipsetTracker);
        this.childlessEventTracker = Objects.requireNonNull(childlessEventTracker);
        this.roster = Objects.requireNonNull(roster);

        totalWeight = RosterUtils.computeTotalWeight(roster);
        selfWeight = RosterUtils.getRosterEntry(roster, selfId.id()).weight();
        maximumPossibleAdvancementWeight = totalWeight - selfWeight;
        maxSnapshotHistorySize = platformContext
                .getConfiguration()
                .getConfigData(EventCreationConfig.class)
                .tipsetSnapshotHistorySize();

        snapshot = new Tipset(roster);
        latestSelfEventTipset = snapshot;
        snapshotHistory.add(snapshot);

        ancientParentLogger = new RateLimitedLogger(logger, platformContext.getTime(), Duration.ofMinutes(1));
        allParentsAreAncientLogger = new RateLimitedLogger(logger, platformContext.getTime(), Duration.ofMinutes(1));
    }

    /**
     * Get the maximum possible tipset advancement weight that a new event can achieve.
     */
    public long getMaximumPossibleAdvancementWeight() {
        return maximumPossibleAdvancementWeight;
    }

    /**
     * Get the current tipset snapshot.
     *
     * @return the current tipset snapshot
     */
    public @NonNull Tipset getSnapshot() {
        return snapshot;
    }

    /**
     * Add an event created by this node and compute the increase in tipset advancement weight. Higher weight changes
     * mean that this event will cause consensus to advance more. An advancement weight change of 0 means that this
     * event did not advance consensus. An advancement weight change close to the total weight means that this event
     * will not do a very good job at advancing consensus. It's impossible to get a perfect advancement weight, since
     * the weight of advancing self events is not included. The maximum advancement weight an event can achieve is equal
     * to the sum of all weights minus this node's weight.
     * <p>
     * Whenever the total advancement weight of a new event exceeds the threshold (2/3 minus self weight), the snapshot
     * is set to be equal to this event's tipset.
     *
     * @param event the event that is being added
     * @return the change in this event's tipset advancement weight compared to the tipset advancement weight of the
     * previous event passed to this method
     */
    public TipsetAdvancementWeight addEventAndGetAdvancementWeight(@NonNull final EventDescriptorWrapper event) {
        Objects.requireNonNull(event);
        if (!event.creator().equals(selfId)) {
            throw new IllegalArgumentException("event creator must be the same as self ID");
        }

        final Tipset eventTipset = tipsetTracker.getTipset(event);
        if (eventTipset == null) {
            throw new IllegalArgumentException("event " + event + " is not in the tipset tracker");
        }

        final TipsetAdvancementWeight advancementWeight = snapshot.getTipAdvancementWeight(selfId, eventTipset);
        if (advancementWeight.advancementWeight() > maximumPossibleAdvancementWeight) {
            throw new IllegalStateException("advancement weight " + advancementWeight
                    + " is greater than the maximum possible weight " + maximumPossibleAdvancementWeight);
        }

        final TipsetAdvancementWeight advancementWeightImprovement = advancementWeight.minus(previousAdvancementWeight);

        if (SUPER_MAJORITY.isSatisfiedBy(advancementWeight.advancementWeight() + selfWeight, totalWeight)) {
            snapshot = eventTipset;
            snapshotHistory.add(snapshot);
            if (snapshotHistory.size() > maxSnapshotHistorySize) {
                snapshotHistory.remove();
            }
            previousAdvancementWeight = ZERO_ADVANCEMENT_WEIGHT;
        } else {
            previousAdvancementWeight = advancementWeight;
        }

        latestSelfEventTipset = eventTipset;

        return advancementWeightImprovement;
    }

    /**
     * Figure out what advancement weight we would get if we created an event with a given list of parents.
     *
     * @param parents the proposed parents of an event
     * @return the advancement weight we would get by creating an event with the given parents
     */
    public TipsetAdvancementWeight getTheoreticalAdvancementWeight(
            @NonNull final List<EventDescriptorWrapper> parents) {
        if (parents.isEmpty()) {
            return ZERO_ADVANCEMENT_WEIGHT;
        }

        final List<Tipset> parentTipsets = new ArrayList<>(parents.size());
        for (final EventDescriptorWrapper parent : parents) {
            final Tipset parentTipset = tipsetTracker.getTipset(parent);

            if (parentTipset == null) {
                // For some reason we are trying to use an ancient parent. In theory possible that a self
                // parent may be ancient. But we shouldn't even be considering non-self parents that are ancient.
                if (!parent.creator().equals(selfId)) {
                    ancientParentLogger.error(
                            EXCEPTION.getMarker(),
                            "When looking at possible parents, we should never "
                                    + "consider ancient parents that are not self parents. "
                                    + "Parent ID = {}, parent generation = {}, minimum generation non-ancient = {}",
                            parent.creator(),
                            parent.eventDescriptor().generation(),
                            tipsetTracker.getEventWindow());
                }
                continue;
            }

            parentTipsets.add(parentTipset);
        }

        if (parentTipsets.isEmpty()) {
            allParentsAreAncientLogger.error(EXCEPTION.getMarker(), "all parents being considered are ancient");
            return ZERO_ADVANCEMENT_WEIGHT;
        }

        // Don't bother advancing the self generation in this theoretical tipset,
        // since self advancement doesn't contribute to tipset advancement weight.
        final Tipset newTipset = Tipset.merge(parentTipsets);

        return snapshot.getTipAdvancementWeight(selfId, newTipset).minus(previousAdvancementWeight);
    }

    /**
     * Compute the current maximum selfishness score with respect to all nodes. This is a measure of how well slow
     * nodes' events are being incorporated in the hashgraph by faster nodes. A high score means slow nodes are being
     * ignored by selfish fast nodes. A low score means slow nodes are being included in consensus. Lower scores are
     * better.
     *
     * @return the current tipset selfishness score
     */
    public int getMaxSelfishnessScore() {
        int selfishness = 0;
        for (final EventDescriptorWrapper eventDescriptorWrapper : childlessEventTracker.getChildlessEvents()) {
            selfishness = Math.max(selfishness, getSelfishnessScoreForNode(eventDescriptorWrapper.creator()));
        }
        return selfishness;
    }

    /**
     * Get the selfishness score with respect to one node, i.e. how much this node is being selfish towards the
     * specified node. A high selfishness score means that we have access to events that could go into our ancestry, but
     * for whatever reason we have decided not to put into our ancestry.
     * <p>
     * The selfishness score is defined as the number of times the snapshot has been advanced without updating the
     * generation of a particular node. For nodes that do not have any events that are legal other parents, the
     * selfishness score is defined to be 0, regardless of how many times the snapshot has been advanced.
     *
     * @param nodeId the node to compute the selfishness score for
     * @return the selfishness score with respect to this node
     */
    public int getSelfishnessScoreForNode(@NonNull final NodeId nodeId) {
        if (latestSelfEventTipset == null) {
            // We can't be selfish if we haven't created any events yet.
            return 0;
        }

        if (latestSelfEventTipset.getTipGenerationForNode(nodeId)
                > snapshotHistory.getLast().getTipGenerationForNode(nodeId)) {
            // Special case: we have advanced this generation since the snapshot was taken.
            return 0;
        }

        int selfishness = 0;
        final long latestGeneration = tipsetTracker.getLatestGenerationForNode(nodeId);

        // Iterate backwards in time until we find an event from the node being added to our ancestry, or if
        // we find that there are no eligible nodes to be added to our ancestry.
        final Iterator<Tipset> iterator = snapshotHistory.descendingIterator();
        Tipset previousTipset = iterator.next();
        while (iterator.hasNext()) {
            final Tipset currentTipset = previousTipset;
            previousTipset = iterator.next();

            final long previousGeneration = previousTipset.getTipGenerationForNode(nodeId);
            final long currentGeneration = currentTipset.getTipGenerationForNode(nodeId);

            if (currentGeneration == latestGeneration || previousGeneration < currentGeneration) {
                // We stop increasing the selfishness score if we observe one of the two following events:
                //
                // 1) we find that the latest generation provided by a node matches a snapshot's generation
                //    (i.e. we've used all events provided by this creator as other parents)
                // 2) we observe an advancement between snapshots, which means that we have put one of this node's
                //    events into our ancestry.
                break;
            }

            selfishness++;
        }

        return selfishness;
    }

    /**
     * Clear the tipset weight calculator to its initial state.
     */
    public void clear() {
        snapshot = new Tipset(roster);
        latestSelfEventTipset = snapshot;
        snapshotHistory.clear();
        snapshotHistory.add(snapshot);
        previousAdvancementWeight = ZERO_ADVANCEMENT_WEIGHT;
    }

    @NonNull
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Total weight: ").append(totalWeight).append("\n");
        sb.append("Maximum possible advancement weight: ")
                .append(maximumPossibleAdvancementWeight)
                .append("\n");
        sb.append("Self weight: ").append(selfWeight).append("\n");
        sb.append("Previous advancement weight: ")
                .append(previousAdvancementWeight)
                .append("\n");
        sb.append("Latest self event tipset: ").append(latestSelfEventTipset).append("\n");
        sb.append("Snapshot: ").append(snapshot).append("\n");
        sb.append("Snapshot history: ").append("\n");
        for (final Tipset tipset : snapshotHistory) {
            sb.append("  - ").append(tipset).append("\n");
        }
        return sb.toString();
    }
}
