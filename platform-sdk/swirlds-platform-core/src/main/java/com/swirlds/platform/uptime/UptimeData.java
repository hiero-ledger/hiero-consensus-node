// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.uptime;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * Uptime data about nodes in the address book.
 */
public class UptimeData {

    /**
     * The round reported if no events have been observed.
     */
    public static final long NO_ROUND = -1;

    private static final Logger logger = LogManager.getLogger(UptimeData.class);

    private final SortedMap<NodeId, NodeUptimeData> data = new TreeMap<>();

    /**
     * Get the consensus time when the most recent consensus event from the given node was observed, or null if no
     * consensus event from the given node has ever been observed.
     *
     * @param id the node ID
     * @return the consensus time when the most recent consensus event from the given node was observed, or null if no
     * consensus event from the given node has ever been received
     */
    @Nullable
    public Instant getLastEventTime(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "id must not be null");
        final NodeUptimeData nodeData = data.get(id);
        if (nodeData == null) {
            return null;
        }
        return nodeData.getLastEventTime();
    }

    /**
     * Get the round when the most recent consensus event from the given node was observed, or {@link UptimeData#NO_ROUND} if no
     * consensus event from the given node has ever been observed.
     *
     * @param id the node ID
     * @return the round when the most recent consensus event from the given node was observed, or {@link UptimeData#NO_ROUND} if
     * no consensus event from the given node has ever been observed
     */
    public long getLastEventRound(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "id must not be null");
        final NodeUptimeData nodeData = data.get(id);
        if (nodeData == null) {
            return NO_ROUND;
        }
        return nodeData.getLastEventRound();
    }

    /**
     * Get the consensus time when the most recent judge from the given node was observed, or null if no judge from the
     * given node has ever been observed.
     *
     * @param id the node ID
     * @return the consensus time when the most recent judge from the given node was observed, or null if no judge
     */
    @Nullable
    public Instant getLastJudgeTime(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "id must not be null");
        final NodeUptimeData nodeData = data.get(id);
        if (nodeData == null) {
            return null;
        }
        return nodeData.getLastJudgeTime();
    }

    /**
     * Get the round when the most recent judge from the given node was observed, or {@link UptimeData#NO_ROUND} if no judge from
     * the given node has ever been observed.
     *
     * @param id the node ID
     * @return the round when the most recent judge from the given node was observed, or {@link UptimeData#NO_ROUND} if no judge
     * from the given node has ever been observed
     */
    public long getLastJudgeRound(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "id must not be null");
        final NodeUptimeData nodeData = data.get(id);
        if (nodeData == null) {
            return NO_ROUND;
        }
        return nodeData.getLastJudgeRound();
    }

    /**
     * Get the set of node IDs that are currently being tracked.
     *
     * @return the set of node IDs that are currently being tracked
     */
    @NonNull
    public Set<NodeId> getTrackedNodes() {
        return new HashSet<>(data.keySet());
    }

    /**
     * Record data about the most recent event received by a node.
     *
     * @param event the event
     * @param round the round number
     */
    public void recordLastEvent(@NonNull final ConsensusEvent event, final long round) {
        final NodeUptimeData nodeData = data.get(event.getCreatorId());
        if (nodeData == null) {
            logger.warn(
                    EXCEPTION.getMarker(), "Node {} is not being tracked by the uptime tracker.", event.getCreatorId());
            return;
        }

        nodeData.setLastEventRound(round).setLastEventTime(event.getConsensusTimestamp());
    }

    /**
     * Record data about the most recent judge received by a node.
     *
     * @param event the judge
     * @param round the round number
     */
    public void recordLastJudge(@NonNull final ConsensusEvent event, final long round) {
        final NodeUptimeData nodeData = data.get(event.getCreatorId());
        if (nodeData == null) {
            logger.warn(
                    EXCEPTION.getMarker(), "Node {} is not being tracked by the uptime tracker.", event.getCreatorId());
            return;
        }
        nodeData.setLastJudgeRound(round).setLastJudgeTime(event.getConsensusTimestamp());
    }

    /**
     * Start tracking data for a new node.
     *
     * @param node the node ID
     */
    public void addNode(@NonNull final NodeId node) {
        Objects.requireNonNull(node, "node must not be null");
        data.put(node, new NodeUptimeData());
    }

    /**
     * Stop tracking data for a node.
     *
     * @param node the node ID
     */
    public void removeNode(@NonNull final NodeId node) {
        Objects.requireNonNull(node, "node must not be null");
        data.remove(node);
    }
}
