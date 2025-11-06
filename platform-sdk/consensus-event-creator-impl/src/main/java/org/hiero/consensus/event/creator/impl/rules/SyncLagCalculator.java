// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import com.hedera.hapi.node.state.roster.Roster;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.hiero.consensus.model.node.NodeId;

// utility class to hold weight and lag in a map value
record WeightAndLag(long weight, long lag) {}

public class SyncLagCalculator {

    /**
     * Mapping of node to weight for all nodes
     */
    private final Map<NodeId, Long> weightMap = new HashMap<>();

    /**
     * Total weight of all nodes except for current node
     */
    private final long totalWeight;

    /**
     * Keep track of how much behind or ahead we are compared to peers based on the latestConsensusRound
     */
    private final ConcurrentMap<NodeId, WeightAndLag> consensusLag = new ConcurrentHashMap<>();

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
        totalWeight = roster.rosterEntries().stream()
                .mapToLong(entry -> {
                    NodeId peerId = NodeId.of(entry.nodeId());
                    weightMap.put(peerId, entry.weight());
                    if (selfId.id() == entry.nodeId()) {
                        return 0;
                    } else {
                        consensusLag.put(peerId, new WeightAndLag(entry.weight(), 0));
                        return entry.weight();
                    }
                })
                .sum();
    }

    public void reportSyncLag(final NodeId nodeId, final long diff) {
        if (selfId.equals(nodeId)) {
            throw new IllegalArgumentException("Reporting sync lag for self is illegal " + nodeId + " " + diff);
        }
        if (!weightMap.containsKey(nodeId)) {
            throw new IllegalArgumentException("Reporting sync lag for node " + nodeId + " which is not in the roster");
        }
        consensusLag.put(nodeId, new WeightAndLag(weightMap.get(nodeId), diff));
    }

    public double getSyncRoundLag() {
        final var lagArray = consensusLag.values().toArray(WeightAndLag[]::new);
        double medianLag;
        if (lagArray.length > 0) {
            medianLag = lagArray[0].lag();
            Arrays.sort(lagArray, Comparator.comparing(WeightAndLag::lag));
            final long correctedTotalWeight = totalWeight - lagArray[0].weight();
            long runningWeight = 0;
            long previousWeight = 0;
            for (int i = 1; i < lagArray.length; i++) {
                runningWeight += lagArray[i].weight();
                if (runningWeight > correctedTotalWeight / 2) {
                    if (i == lagArray.length - 1) {
                        medianLag = lagArray[i].lag();
                    } else {
                        medianLag = lerp(
                                previousWeight,
                                runningWeight,
                                correctedTotalWeight / 2.0,
                                lagArray[i - 1].lag(),
                                lagArray[i].lag());
                    }
                    break;
                }
                previousWeight = runningWeight;
            }
        } else {
            medianLag = 0.0;
        }

        return Math.max(medianLag, 0);
    }

    /**
     * Linear interpolation between two points. Flat extension if x&lt;x1 or x&gt;x2
     *
     * @param x1 left bound
     * @param x2 right bound
     * @param x  point where we interpolate
     * @param y1 value at left bound
     * @param y2 value at right bound
     * @return value linearly interpolated between left and right bounds at point x
     */
    private double lerp(final double x1, final double x2, final double x, final double y1, final double y2) {
        if (x <= x1) {
            return y1;
        }
        if (x >= x2) {
            return y2;
        }
        return y1 + (y2 - y1) * (x - x1) / (x2 - x1);
    }
}
