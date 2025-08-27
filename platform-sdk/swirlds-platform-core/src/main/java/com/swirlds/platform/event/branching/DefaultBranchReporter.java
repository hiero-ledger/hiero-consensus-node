// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.branching;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.Threshold;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * This class is responsible for logging and producing metrics when a branch is observed.
 */
public class DefaultBranchReporter implements BranchReporter {

    private static final Logger logger = LogManager.getLogger(DefaultBranchReporter.class);

    /**
     * Keep a rate limited logger per node. We want to log when we see a node branching, but shouldn't allow a single
     * node to spam the logs.
     */
    private final Map<NodeId, RateLimitedLogger> nodeLoggers = new HashMap<>();

    private final RateLimitedLogger excessiveBranchingLogger;

    /**
     * The current roster.
     */
    private final Roster currentRoster;

    /** A map of RosterEntries. */
    private final Map<Long, RosterEntry> rosterMap;

    /** The total weight of all RosterEntries. */
    private final long rosterTotalWeight;

    /**
     * The node IDs of the nodes in the network in sorted order, provides deterministic iteration order.
     */
    private final List<NodeId> nodes = new ArrayList<>();

    /**
     * The current event window.
     */
    private EventWindow currentEventWindow;

    /**
     * The most recent non-ancient branching event for each node (not present or null if there are none).
     */
    private final Map<NodeId, EventDescriptorWrapper> mostRecentBranchingEvents = new HashMap<>();

    /**
     * The total number of nodes that currently have a non-ancient branching event.
     */
    private int branchingCount;

    /**
     * The sum of the consensus weight for all nodes that currently have a non-ancient branching event.
     */
    private long branchingWeight;

    /**
     * Metrics for the branch detector.
     */
    private final BranchingMetrics metrics;

    /**
     * Constructor.
     *
     * @param platformContext the platform context
     * @param currentRoster   the current roster
     */
    public DefaultBranchReporter(@NonNull final PlatformContext platformContext, @NonNull final Roster currentRoster) {

        this.currentRoster = Objects.requireNonNull(currentRoster);
        this.rosterMap = RosterUtils.toMap(currentRoster);
        this.rosterTotalWeight = RosterUtils.computeTotalWeight(currentRoster);

        // The stream MUST be sequential to modify external collections in forEach().
        currentRoster.rosterEntries().stream().map(re -> NodeId.of(re.nodeId())).forEach(nodeId -> {
            nodes.add(nodeId);
            nodeLoggers.put(nodeId, new RateLimitedLogger(logger, platformContext.getTime(), Duration.ofMinutes(10)));
        });

        excessiveBranchingLogger = new RateLimitedLogger(logger, platformContext.getTime(), Duration.ofMinutes(10));

        Collections.sort(nodes);

        metrics = new BranchingMetrics(platformContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportBranch(@NonNull final PlatformEvent event) {
        if (currentEventWindow == null) {
            throw new IllegalStateException("Event window must be set before reporting branches");
        }

        if (currentEventWindow.isAncient(event)) {
            // Ignore ancient events.
            return;
        }

        final NodeId creator = event.getCreatorId();
        nodeLoggers.get(creator).error(EXCEPTION.getMarker(), "Node {} is branching", creator);

        final EventDescriptorWrapper previousBranchingEvent =
                mostRecentBranchingEvents.put(creator, event.getDescriptor());

        if (previousBranchingEvent == null) {
            // This node is now branching but wasn't previously.
            branchingCount++;
            branchingWeight += rosterMap.get(creator.id()).weight();
        }

        metrics.reportBranchingEvent();
        metrics.reportBranchingNodeCount(branchingCount);
        final double fraction = (double) branchingWeight / rosterTotalWeight;
        metrics.reportBranchingWeightFraction(fraction);

        if (Threshold.STRONG_MINORITY.isSatisfiedBy(branchingWeight, rosterTotalWeight)) {
            // Uh oh. We've violated our assumption that >2/3 nodes in the network are honest.

            final List<NodeId> branchingNodes = new ArrayList<>();
            for (final NodeId nodeId : nodes) {
                if (mostRecentBranchingEvents.get(nodeId) != null) {
                    branchingNodes.add(nodeId);
                }
            }
            final StringBuilder sb = new StringBuilder();
            for (int index = 0; index < branchingNodes.size(); index++) {
                sb.append(branchingNodes.get(index));
                if (index < branchingNodes.size() - 1) {
                    sb.append(", ");
                }
            }

            excessiveBranchingLogger.fatal(
                    EXCEPTION.getMarker(),
                    "Excessive branching detected! {} weight, fraction: {}, nodes: {}",
                    branchingWeight,
                    fraction,
                    sb);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        currentEventWindow = eventWindow;

        for (final NodeId nodeId : nodes) {
            final EventDescriptorWrapper mostRecentBranchingEvent = mostRecentBranchingEvents.get(nodeId);
            if (mostRecentBranchingEvent != null && eventWindow.isAncient(mostRecentBranchingEvent)) {
                // Branching event is ancient, forget it.
                mostRecentBranchingEvents.put(nodeId, null);
                branchingCount--;
                branchingWeight -= rosterMap.get(nodeId.id()).weight();
            }
        }
        metrics.reportBranchingNodeCount(branchingCount);
        metrics.reportBranchingWeightFraction((double) branchingWeight / rosterTotalWeight);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        currentEventWindow = null;
        branchingCount = 0;
        branchingWeight = 0;

        metrics.reportBranchingNodeCount(0);
        metrics.reportBranchingWeightFraction(0);
    }
}
