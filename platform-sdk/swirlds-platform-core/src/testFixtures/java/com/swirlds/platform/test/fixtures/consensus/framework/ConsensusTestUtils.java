// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework;

import com.swirlds.common.utility.Threshold;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConsensusTestUtils {
    /**
     * Get a set of node ids such that their stake is at least a strong minority but not a super
     * majority. Each group of nodes (the partitioned node and non-partitions nodes) has a strong
     * minority.
     *
     * @param nodeStakes the stakes of each node in the network
     * @return the set of node ids
     */
    public static @NonNull Set<Integer> getStrongMinorityNodes(@NonNull final List<Long> nodeStakes) {
        final Set<Integer> partitionedNodes = new HashSet<>();
        final long totalStake = nodeStakes.stream().reduce(0L, Long::sum);
        long partitionedStake = 0L;
        for (int i = 0; i < nodeStakes.size(); i++) {
            // If we have enough partitioned nodes to make a strong minority, stop and return
            if (Threshold.STRONG_MINORITY.isSatisfiedBy(partitionedStake, totalStake)) {
                break;
            }
            // If adding this node to the partition would give the partition a super majority, skip
            // this node because
            // the remaining group of nodes would not have a strong minority
            if (Threshold.SUPER_MAJORITY.isSatisfiedBy(partitionedStake + nodeStakes.get(i), totalStake)) {
                continue;
            }
            partitionedNodes.add(i);
            partitionedStake += nodeStakes.get(i);
        }
        System.out.println("Partitioned nodes: " + partitionedNodes);
        System.out.printf(
                "\nPartition has %s (%s%%) of %s total stake.%n",
                partitionedStake, (((double) partitionedStake) / totalStake) * 100, totalStake);
        return partitionedNodes;
    }

    /**
     * Get a set of node ids such that their stake is less than a strong minority. Nodes not in the
     * returned set will have a super majority and can continue to reach consensus.
     *
     * @param nodeStakes the stakes of each node in the network
     * @return the set of node ids
     */
    public static @NonNull Set<Integer> getSubStrongMinorityNodes(@NonNull final List<Long> nodeStakes) {
        final Set<Integer> partitionedNodes = new HashSet<>();
        final long totalStake = nodeStakes.stream().reduce(0L, Long::sum);
        long partitionedStake = 0L;
        for (int i = 0; i < nodeStakes.size(); i++) {
            // Leave at least two nodes not in the partition set so that gossip can continue in both
            // the partitioned nodes and non-partitioned nodes
            if (partitionedNodes.size() + 2 == nodeStakes.size()) {
                break;
            }
            // If adding this node to the partition would give the partition a strong minority, skip
            // this node because
            // the remaining group of nodes would not have a super majority
            if (Threshold.STRONG_MINORITY.isSatisfiedBy(partitionedStake + nodeStakes.get(i), totalStake)) {
                continue;
            }
            partitionedNodes.add(i);
            partitionedStake += nodeStakes.get(i);
        }
        System.out.println("Partitioned nodes: " + partitionedNodes);
        System.out.printf(
                "\nPartition has %s (%s%%) of %s total stake.%n",
                partitionedStake, (((double) partitionedStake) / totalStake) * 100, totalStake);
        return partitionedNodes;
    }
}
