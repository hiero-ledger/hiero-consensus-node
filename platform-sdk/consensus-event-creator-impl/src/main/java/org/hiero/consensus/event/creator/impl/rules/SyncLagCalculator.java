// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import com.hedera.hapi.node.state.roster.Roster;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

// utility class to hold weight and lag in a map value
record WeightAndLag(long weight, long lag) {}

/**
 * Utility class for calculating median lag compared to the current round reported by peers. Median is computed based on
 * the weights of the nodes.
 */
public class SyncLagCalculator {

    /**
     * Mapping of node to weight for all nodes
     */
    private final Map<NodeId, Long> weightMap = new HashMap<>();

    /**
     * Total weight of all nodes except for current node
     */
    private final long otherNodesTotalWeight;

    /**
     * Keep track of how much behind or ahead we are compared to peers based on the latestConsensusRound
     */
    private final Map<NodeId, WeightAndLag> consensusLag = new HashMap<>();

    /**
     * Current node id
     */
    private final NodeId selfId;

    /**
     * Constructs new lag calculator
     *
     * @param selfId current node id
     * @param roster roster of all the nodes in network
     */
    public SyncLagCalculator(final NodeId selfId, final Roster roster) {
        this.selfId = selfId;
        otherNodesTotalWeight = roster.rosterEntries().stream()
                .peek(entry -> weightMap.put(NodeId.of(entry.nodeId()), entry.weight()))
                .peek(entry -> {
                    if (selfId.id() != entry.nodeId()) {
                        consensusLag.put(NodeId.of(entry.nodeId()), new WeightAndLag(entry.weight(), 0));
                    }
                })
                .mapToLong(entry -> {
                    if (selfId.id() == entry.nodeId()) {
                        return 0;
                    } else {
                        return entry.weight();
                    }
                })
                .sum();
    }

    /**
     * Update round lag as computed against specific peer
     *
     * @param nodeId peer against which lag is reported
     * @param diff   lag in number of rounds
     */
    public void reportSyncLag(final NodeId nodeId, final long diff) {
        if (selfId.equals(nodeId)) {
            throw new IllegalArgumentException("Reporting sync lag for self is illegal " + nodeId + " " + diff);
        }
        if (!weightMap.containsKey(nodeId)) {
            throw new IllegalArgumentException("Reporting sync lag for node " + nodeId + " which is not in the roster");
        }
        consensusLag.put(nodeId, new WeightAndLag(weightMap.get(nodeId), diff));
    }

    /**
     * Retrieve median round lag computed from syncs. It is median based on weights, so more important nodes skew the
     * median into their favor
     *
     * @return median of lag (which, in most cases, will be linearly interpolated between two 'middle' nodes)
     */
    public double getSyncRoundLag() {
        final var lagArray = consensusLag.values().toArray(WeightAndLag[]::new);
        double medianLag;
        if (otherNodesTotalWeight > 0) {
            // shut up compiler about the loop exit
            medianLag = lagArray[0].lag();
            // we need to sort everything based on lags to look for median
            Arrays.sort(lagArray, Comparator.comparing(WeightAndLag::lag));

            final long correctedTotalWeight = otherNodesTotalWeight;
            long runningWeight = 0;
            for (int i = 0; i < lagArray.length; i++) {
                runningWeight += lagArray[i].weight();
                if (runningWeight == correctedTotalWeight / 2 && i < lagArray.length - 2) {
                    // are we exactly on the edge? if yes, take weighted average of two entries
                    medianLag = (lagArray[i].lag() * lagArray[i].weight()
                                    + lagArray[i + 1].lag() * lagArray[i + 1].weight())
                            / ((double) lagArray[i].weight() + lagArray[i + 1].weight());
                    break;
                } else if (runningWeight > correctedTotalWeight / 2) {
                    // have we just crossed the threshold? it must mean current entry is the median
                    medianLag = lagArray[i].lag();
                    break;
                }
            }
        } else {
            // this should not happen normally, but sometimes we test single-node networks in unit tests
            medianLag = 0.0;
        }

        return Math.max(medianLag, 0);
    }
}
