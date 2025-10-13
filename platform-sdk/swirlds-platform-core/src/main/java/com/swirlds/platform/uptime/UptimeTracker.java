// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.uptime;

import static com.swirlds.common.units.TimeUnit.UNIT_MICROSECONDS;
import static com.swirlds.common.units.TimeUnit.UNIT_NANOSECONDS;
import static com.swirlds.platform.uptime.UptimeData.NO_ROUND;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.SelfEventReachedConsensusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.hiero.base.CompareTo;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Monitors the uptime of nodes in the network.
 */
public class UptimeTracker {

    private final NodeId selfId;
    private final Time time;
    private final UptimeMetrics uptimeMetrics;
    private final Duration degradationThreshold;

    /**
     * The last consensus time a self event was created.
     */
    private final AtomicReference<Instant> lastSelfEventTime = new AtomicReference<>();

    /**
     * Enables submitting platform status actions.
     */
    private final StatusActionSubmitter statusActionSubmitter;

    /**
     * The uptime data for all the nodes. It's package-private for testing purposes.
     */
    final UptimeData uptimeData;

    /**
     * Construct a new uptime detector.
     *
     * @param platformContext       the platform context
     * @param roster                the current roster
     * @param statusActionSubmitter enables submitting platform status actions
     * @param selfId                the ID of this node
     * @param time                  a source of time
     */
    public UptimeTracker(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final NodeId selfId,
            @NonNull final Time time) {

        this.selfId = Objects.requireNonNull(selfId, "selfId must not be null");
        this.time = Objects.requireNonNull(time);
        this.statusActionSubmitter = Objects.requireNonNull(statusActionSubmitter);
        this.degradationThreshold = platformContext
                .getConfiguration()
                .getConfigData(UptimeConfig.class)
                .degradationThreshold();
        this.uptimeMetrics = new UptimeMetrics(platformContext.getMetrics(), roster, this::isSelfDegraded);
        this.uptimeData = new UptimeData();
    }

    /**
     * Look at the events in a round to determine which nodes are up and which nodes are down.
     *
     * @param round       the round to analyze
     */
    public void handleRound(@NonNull final ConsensusRound round) {

        if (round.isEmpty()) {
            return;
        }

        final Instant start = time.now();

        addAndRemoveNodes(uptimeData, round.getConsensusRoster());
        final Map<NodeId, ConsensusEvent> lastEventsInRoundByCreator = new HashMap<>();
        final Map<NodeId, ConsensusEvent> judgesByCreator = new HashMap<>();
        scanRound(round, lastEventsInRoundByCreator, judgesByCreator);
        updateUptimeData(
                round.getConsensusRoster(),
                uptimeData,
                lastEventsInRoundByCreator,
                judgesByCreator,
                round.getRoundNum());
        reportUptime(round.getConsensusRoster(), uptimeData, round.getConsensusTimestamp(), round.getRoundNum());

        final Instant end = time.now();
        final Duration elapsed = Duration.between(start, end);
        uptimeMetrics
                .getUptimeComputationTimeMetric()
                .update(UNIT_NANOSECONDS.convertTo(elapsed.toNanos(), UNIT_MICROSECONDS));
    }

    /**
     * Add and remove nodes as necessary. Will only make changes if roster membership in this round is different
     * from the roster in the previous round, or at genesis.
     *
     * @param uptimeData  the uptime data
     * @param roster the current roster
     */
    private void addAndRemoveNodes(@NonNull final UptimeData uptimeData, @NonNull final Roster roster) {
        final Set<NodeId> rosterNodes = roster.rosterEntries().stream()
                .map(entry -> NodeId.of(entry.nodeId()))
                .collect(Collectors.toSet());
        final Set<NodeId> trackedNodes = uptimeData.getTrackedNodes();
        for (final NodeId nodeId : rosterNodes) {
            if (!trackedNodes.contains(nodeId)) {
                // node was added
                uptimeMetrics.addMetricsForNode(nodeId);
                uptimeData.addNode(nodeId);
            }
        }
        for (final NodeId nodeId : trackedNodes) {
            if (!rosterNodes.contains(nodeId)) {
                // node was removed
                uptimeMetrics.removeMetricsForNode(nodeId);
                uptimeData.removeNode(nodeId);
            }
        }
    }

    /**
     * Check if this node should consider itself to be degraded.
     *
     * @return true if this node should consider itself to be degraded
     */
    public boolean isSelfDegraded() {
        final Instant eventTime = lastSelfEventTime.get();
        if (eventTime == null) {
            // Consider a node to be degraded until it has its first event reach consensus.
            return true;
        }

        final Instant now = time.now();
        final Duration durationSinceLastEvent = Duration.between(eventTime, now);
        return CompareTo.isGreaterThan(durationSinceLastEvent, degradationThreshold);
    }

    /**
     * Scan all events in the round.
     *
     * @param round                      the round
     * @param lastEventsInRoundByCreator the last event in the round by creator, is updated by this method
     * @param judgesByCreator            the judges by creator, is updated by this method
     */
    private void scanRound(
            @NonNull final Round round,
            @NonNull final Map<NodeId, ConsensusEvent> lastEventsInRoundByCreator,
            @NonNull final Map<NodeId, ConsensusEvent> judgesByCreator) {

        // capture previous self event consensus timestamp, so we can tell if the current round contains a
        // new self event
        final Instant previousSelfEventConsensusTimestamp = lastSelfEventTime.get();

        round.forEach(event -> {
            lastEventsInRoundByCreator.put(event.getCreatorId(), event);
            // Temporarily disabled until we properly detect judges in a round
            //            if (((EventImpl) event).isFamous()) {
            //                judgesByCreator.put(event.getCreatorId(), event);
            //            }
        });

        final ConsensusEvent lastSelfEvent = lastEventsInRoundByCreator.get(selfId);
        if (lastSelfEvent != null) {
            final Instant lastSelfEventConsensusTimestamp = lastSelfEvent.getConsensusTimestamp();
            if (!lastSelfEventConsensusTimestamp.equals(previousSelfEventConsensusTimestamp)) {
                lastSelfEventTime.set(lastSelfEventConsensusTimestamp);

                // the action receives the wall clock time, NOT the consensus timestamp
                statusActionSubmitter.submitStatusAction(new SelfEventReachedConsensusAction(time.now()));
            }
        }
    }

    /**
     * Update the uptime data based on the events in this round.
     *
     * @param roster                     the current roster
     * @param uptimeData                 the uptime data to be updated
     * @param lastEventsInRoundByCreator the last event in the round by creator
     * @param judgesByCreator            the judges by creator
     * @param roundNum                   the round number
     */
    private void updateUptimeData(
            @NonNull final Roster roster,
            @NonNull final UptimeData uptimeData,
            @NonNull final Map<NodeId, ConsensusEvent> lastEventsInRoundByCreator,
            @NonNull final Map<NodeId, ConsensusEvent> judgesByCreator,
            final long roundNum) {

        for (final RosterEntry rosterEntry : roster.rosterEntries()) {
            final ConsensusEvent lastEvent = lastEventsInRoundByCreator.get(NodeId.of(rosterEntry.nodeId()));
            if (lastEvent != null) {
                uptimeData.recordLastEvent(lastEvent, roundNum);
            }

            // Temporarily disabled until we properly detect judges in a round
            //            final ConsensusEvent judge = judgesByCreator.get(address.getNodeId());
            //            if (judge != null) {
            //                uptimeData.recordLastJudge((EventImpl) judge);
            //            }
        }
    }

    /**
     * Report the uptime data.
     *
     * @param uptimeData the uptime data
     */
    private void reportUptime(
            @NonNull final Roster roster,
            @NonNull final UptimeData uptimeData,
            @NonNull final Instant lastRoundEndTime,
            final long currentRound) {

        long nonDegradedConsensusWeight = 0;
        for (final RosterEntry entry : roster.rosterEntries()) {
            final NodeId id = NodeId.of(entry.nodeId());

            final Instant lastConsensusEventTime = uptimeData.getLastEventTime(id);
            if (lastConsensusEventTime != null) {
                final Duration timeSinceLastConsensusEvent = Duration.between(lastConsensusEventTime, lastRoundEndTime);

                if (CompareTo.isLessThanOrEqualTo(timeSinceLastConsensusEvent, degradationThreshold)) {
                    nonDegradedConsensusWeight += entry.weight();
                }
            }

            final long lastEventRound = uptimeData.getLastEventRound(id);
            if (lastEventRound != NO_ROUND) {
                uptimeMetrics.getRoundsSinceLastConsensusEventMetric(id).update(currentRound - lastEventRound);
            }

            // Temporarily disabled until we properly detect judges in a round
            //            final long lastJudgeRound = uptimeData.getLastJudgeRound(id);
            //            if (lastJudgeRound != NO_ROUND) {
            //                uptimeMetrics.getRoundsSinceLastJudgeMetric(id).update(currentRound - lastJudgeRound);
            //            }
        }
        final double fractionOfNetworkAlive =
                (double) nonDegradedConsensusWeight / RosterUtils.computeTotalWeight(roster);
        uptimeMetrics.getHealthyNetworkFraction().update(fractionOfNetworkAlive);
    }
}
