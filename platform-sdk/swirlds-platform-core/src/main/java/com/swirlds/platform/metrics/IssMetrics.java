// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Metrics for ISS events.
 */
public class IssMetrics {

    private final Roster roster;

    /**
     * The current number of nodes experiencing an ISS.
     */
    private int issCount;

    /**
     * The highest round number observed so far.
     */
    private long highestRound = Long.MIN_VALUE;

    /**
     * The metric for the current number of nodes in an ISS state.
     */
    private final IntegerGauge issCountGauge;

    /**
     * The current total weight tied up by ISS events.
     */
    private long issWeight;

    /**
     * The metric for the current weight tied up by ISS events.
     */
    private final LongGauge issWeightGage;

    /**
     * The ISS status of a node.
     */
    private static class IssStatus {
        private boolean hasIss = false;
        private long round = Long.MIN_VALUE;

        /**
         * Check if this node is currently experiencing an ISS.
         */
        public boolean hasIss() {
            return hasIss;
        }

        /**
         * Set if this node is currently experiencing an ISS.
         */
        public void setHasIss(final boolean hasIss) {
            this.hasIss = hasIss;
        }

        /**
         * Get the round number that last reported the ISS status.
         */
        public long getRound() {
            return round;
        }

        /**
         * Set the round number that last reported the ISS status.
         */
        public void setRound(final long round) {
            this.round = round;
        }
    }

    /**
     * The current ISS status of nodes.
     */
    private final Map<NodeId, IssStatus> issDataByNode = new HashMap<>();

    /**
     * Constructor of {@code IssMetrics}
     *
     * @param metrics
     * 		a reference to the metrics-system
     * @param roster the active roster for this network
     * @throws IllegalArgumentException
     * 		if {@code metrics} is {@code null}
     */
    public IssMetrics(@NonNull final Metrics metrics, @NonNull final Roster roster) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        this.roster = Objects.requireNonNull(roster, "roster must not be null");

        issCountGauge = metrics.getOrCreate(new IntegerGauge.Config(Metrics.INTERNAL_CATEGORY, "issCount")
                .withDescription("the number of nodes that currently disagree with the consensus hash"));

        issWeightGage = metrics.getOrCreate(new LongGauge.Config(Metrics.INTERNAL_CATEGORY, "issWeight")
                .withDescription("the amount of weight tied up by ISS events"));

        for (final RosterEntry node : roster.rosterEntries()) {
            issDataByNode.put(NodeId.of(node.nodeId()), new IssStatus());
        }
    }

    /**
     * Get the current number of nodes currently thought to be in an ISS state.
     */
    public int getIssCount() {
        return issCount;
    }

    /**
     * Get the current weight currently tied up in an ISS.
     */
    public long getIssWeight() {
        return issWeight;
    }

    /**
     * Report the status of a node.
     *
     * @param round
     * 		the round number
     * @param nodeId
     * 		the ID of the node that submitted the hash
     * @param nodeHash
     * 		the hash computed by the node
     * @param consensusHash
     * 		the consensus hash computed by the network
     */
    public void stateHashValidityObserver(
            @NonNull final Long round,
            @NonNull final NodeId nodeId,
            @NonNull final Hash nodeHash,
            @NonNull final Hash consensusHash) {
        Objects.requireNonNull(round, "round must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(nodeHash, "nodeHash must not be null");
        Objects.requireNonNull(consensusHash, "consensusHash must not be null");

        final boolean hasIss = !nodeHash.equals(consensusHash);

        final IssStatus issStatus = issDataByNode.get(nodeId);
        if (issStatus == null) {
            throw new IllegalArgumentException("Node " + nodeId + " is not being tracked");
        }

        highestRound = Math.max(highestRound, round);

        if (issStatus.getRound() >= round) {
            // Don't allow old data to overwrite new data
            return;
        }
        issStatus.setRound(round);

        if (issStatus.hasIss() != hasIss) {
            final long weight = RosterUtils.getRosterEntry(roster, nodeId.id()).weight();
            if (hasIss) {
                issCount++;
                issWeight += weight;
            } else {
                issCount--;
                issWeight -= weight;
            }
            issCountGauge.set(issCount);
            issWeightGage.set(issWeight);
            issStatus.setHasIss(hasIss);
        }
    }

    /**
     * Report the existence of a catastrophic ISS.
     *
     * @param round
     * 		the round of the ISS
     */
    public void catastrophicIssObserver(final long round) {
        if (round <= highestRound) {
            // Don't report old data
            return;
        }

        highestRound = round;
        for (final IssStatus status : issDataByNode.values()) {
            status.setHasIss(true);
            status.setRound(round);
        }

        issCount = roster.rosterEntries().size();
        issWeight = RosterUtils.computeTotalWeight(roster);

        issCountGauge.set(issCount);
        issWeightGage.set(issWeight);
    }
}
