// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.utils;

import com.swirlds.common.utility.Threshold;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.hiero.otter.fixtures.Node;

/**
 * Utility class for calculating quorums based on node weights.
 */
public class QuorumCalculator {

    private QuorumCalculator() {}

    /**
     * Optimization goal for partition finding.
     */
    private enum OptimizationGoal {
        /** Find the partition with minimum weight */
        MINIMIZE,
        /** Find the partition with maximum weight */
        MAXIMIZE
    }

    /**
     * Calculates the smallest subset of nodes by weight that forms a strong minority but NOT a majority.
     * A strong minority is defined as having weight >= 1/3 of the total weight.
     *
     * @param nodes the list of nodes to partition
     * @return the subset with minimum total weight that forms a strong minority but not a majority
     * @throws NullPointerException if {@code nodes} is {@code null}
     * @throws IllegalArgumentException if no valid partition exists (e.g., all partitions that are strong minorities
     *                                  are also majorities)
     */
    @NonNull
    public static List<Node> smallestStrongMinority(@NonNull final List<Node> nodes) {
        return findPartitionByWeight(nodes, Threshold.STRONG_MINORITY, Threshold.MAJORITY, OptimizationGoal.MINIMIZE);
    }

    /**
     * Calculates the smallest subset of nodes by weight that forms a majority but NOT a super majority.
     * A majority is defined as having weight > 1/2 of the total weight.
     *
     * @param nodes the list of nodes to partition
     * @return the subset with minimum total weight that forms a majority but not a super majority
     * @throws NullPointerException if {@code nodes} is {@code null}
     * @throws IllegalArgumentException if no valid partition exists (e.g., all partitions that are majorities
     *                                  are also super majorities)
     */
    @NonNull
    public static List<Node> smallestMajority(@NonNull final List<Node> nodes) {
        return findPartitionByWeight(nodes, Threshold.MAJORITY, Threshold.SUPER_MAJORITY, OptimizationGoal.MINIMIZE);
    }

    /**
     * Calculates the smallest subset of nodes by weight that forms a super majority.
     * A super majority is defined as having weight > 2/3 of the total weight.
     *
     * @param nodes the list of nodes to partition
     * @return the subset with minimum total weight that forms a super majority
     * @throws NullPointerException if {@code nodes} is {@code null}
     * @throws IllegalArgumentException if no valid partition exists
     */
    @NonNull
    public static List<Node> smallestSuperMajority(@NonNull final List<Node> nodes) {
        return findPartitionByWeight(nodes, Threshold.SUPER_MAJORITY, null, OptimizationGoal.MINIMIZE);
    }

    /**
     * Calculates the largest subset of nodes by weight that is a sub-strong minority (weight < 1/3 of total weight).
     * A sub-strong minority does NOT satisfy the strong minority threshold.
     *
     * @param nodes the list of nodes to partition
     * @return the subset with maximum total weight that is a sub-strong minority (weight < 1/3)
     * @throws NullPointerException if {@code nodes} is {@code null}
     * @throws IllegalArgumentException if no valid partition exists (e.g., all non-empty partitions are strong minorities)
     */
    @NonNull
    public static List<Node> largestSubStrongMinority(@NonNull final List<Node> nodes) {
        return findPartitionByWeight(nodes, null, Threshold.STRONG_MINORITY, OptimizationGoal.MAXIMIZE);
    }

    /**
     * Finds a subset that satisfies the given constraints while optimizing for minimum or maximum weight.
     * Uses a brute force algorithm that checks all possible subsets.
     *
     * @param nodes the list of nodes to partition
     * @param lowerThreshold the threshold that must be satisfied (null if no lower bound)
     * @param upperThreshold the threshold that must NOT be satisfied (null if no upper bound)
     * @param goal whether to minimize or maximize the partition weight
     * @return the subset with optimal weight that satisfies the conditions
     * @throws NullPointerException if {@code nodes} is {@code null}
     * @throws IllegalArgumentException if no valid partition exists
     */
    @NonNull
    private static List<Node> findPartitionByWeight(
            @NonNull final List<Node> nodes,
            @Nullable final Threshold lowerThreshold,
            @Nullable final Threshold upperThreshold,
            @NonNull final OptimizationGoal goal) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Cannot find partition for empty node list");
        }

        final long totalWeight = nodes.stream().mapToLong(Node::weight).sum();
        List<Node> bestPartition = null;
        long bestWeight = goal == OptimizationGoal.MINIMIZE ? Long.MAX_VALUE : -1;

        // Try all possible subsets
        final int n = nodes.size();
        final int totalSubsets = 1 << n; // 2^n

        for (int mask = 1; mask < totalSubsets; mask++) {
            final BitSet subset = BitSet.valueOf(new long[] {mask});

            // Build the current subset and calculate its weight
            // Skip nodes with zero weight as they don't contribute to thresholds
            final List<Node> currentSubset = new ArrayList<>();
            long currentWeight = 0;

            for (int i = subset.nextSetBit(0); i >= 0; i = subset.nextSetBit(i + 1)) {
                final Node node = nodes.get(i);
                final long nodeWeight = node.weight();
                if (nodeWeight > 0) {
                    currentSubset.add(node);
                    currentWeight += nodeWeight;
                }
            }

            // Check if this subset satisfies the lower threshold (if specified)
            if (lowerThreshold != null && !lowerThreshold.isSatisfiedBy(currentWeight, totalWeight)) {
                continue;
            }

            // Check if this subset does NOT satisfy the upper threshold (if specified)
            if (upperThreshold != null && upperThreshold.isSatisfiedBy(currentWeight, totalWeight)) {
                continue;
            }

            // This subset is valid, check if it's better than the current best
            final boolean isBetter =
                    goal == OptimizationGoal.MINIMIZE ? currentWeight < bestWeight : currentWeight > bestWeight;

            if (isBetter) {
                bestWeight = currentWeight;
                bestPartition = new ArrayList<>(currentSubset);
            }
        }

        if (bestPartition == null) {
            // Build error message based on constraints
            final StringBuilder message = new StringBuilder("No valid partition found");
            if (lowerThreshold != null && upperThreshold != null) {
                message.append(" that satisfies ")
                        .append(lowerThreshold.name())
                        .append(" but not ")
                        .append(upperThreshold.name());
            } else if (lowerThreshold != null) {
                message.append(" that satisfies ").append(lowerThreshold.name());
            } else if (upperThreshold != null) {
                message.append(" that does not satisfy ").append(upperThreshold.name());
            }
            throw new IllegalArgumentException(message.toString());
        }

        return bestPartition;
    }
}
