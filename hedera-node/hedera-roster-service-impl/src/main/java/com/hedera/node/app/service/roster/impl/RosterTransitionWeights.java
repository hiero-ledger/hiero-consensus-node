// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Stream;

/**
 * Represents the weights of the nodes in a roster transition.
 * @param sourceNodeWeights the weights of the nodes in the source roster
 * @param targetNodeWeights the weights of the nodes in the target roster
 * @param sourceWeightThreshold the weight required for at least 1/3 weight in the source roster
 * @param targetWeightThreshold the weight required for strictly more than 2/3 weight in the target roster
 */
public record RosterTransitionWeights(
        @NonNull SortedMap<Long, Long> sourceNodeWeights,
        @NonNull SortedMap<Long, Long> targetNodeWeights,
        long sourceWeightThreshold,
        long targetWeightThreshold) {

    public RosterTransitionWeights(
            @NonNull final SortedMap<Long, Long> sourceNodeWeights,
            @NonNull final SortedMap<Long, Long> targetNodeWeights) {
        this(
                requireNonNull(sourceNodeWeights),
                requireNonNull(targetNodeWeights),
                atLeastOneThirdOfTotal(sourceNodeWeights),
                moreThanTwoThirdsOfTotal(targetNodeWeights));
    }

    /**
     * Represents the weight of a node in a roster.
     * @param nodeId the ID of the node
     * @param weight the weight of the node
     */
    public record NodeWeight(long nodeId, long weight) implements Comparable<NodeWeight> {
        private static final Comparator<NodeWeight> NODE_ID_COMPARATOR = Comparator.comparingLong(NodeWeight::nodeId);

        @Override
        public int compareTo(@NonNull final NodeWeight that) {
            return NODE_ID_COMPARATOR.compare(this, that);
        }
    }

    /**
     * Returns the source node ids in the target roster.
     * @return the source node ids in the target roster
     */
    public Set<Long> sourceNodeIds() {
        return sourceNodeWeights.keySet();
    }

    /**
     * Returns the target node ids in the source roster.
     * @return the target node ids in the source roster
     */
    public Set<Long> targetNodeIds() {
        return targetNodeWeights.keySet();
    }

    /**
     * Returns whether the source roster has a strict majority of weight in the target roster.
     */
    public boolean sourceNodesHaveTargetThreshold() {
        return sourceNodeWeights.keySet().stream()
                        .filter(targetNodeWeights::containsKey)
                        .mapToLong(targetNodeWeights::get)
                        .sum()
                >= targetWeightThreshold;
    }

    /**
     * Returns the weights of the nodes in the source roster in ascending order of node ID.
     * @return the weights of the nodes in the source roster
     */
    public Stream<NodeWeight> orderedSourceWeights() {
        return sourceNodeWeights.entrySet().stream()
                .map(entry -> new NodeWeight(entry.getKey(), entry.getValue()))
                .sorted();
    }

    /**
     * Returns the weights of the nodes in the source roster in ascending order of node ID.
     * @return the weights of the nodes in the source roster
     */
    public Stream<NodeWeight> orderedTargetWeights() {
        return targetNodeWeights.entrySet().stream()
                .map(entry -> new NodeWeight(entry.getKey(), entry.getValue()))
                .sorted();
    }

    /**
     * Returns the weight of a node in the source roster.
     * @param nodeId the ID of the node
     * @return the weight of the node
     */
    public long sourceWeightOf(final long nodeId) {
        return sourceNodeWeights.getOrDefault(nodeId, 0L);
    }

    /**
     * Returns the total weight of the source roster.
     */
    public long totalSourceWeight() {
        return sourceNodeWeights.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Returns whether given node has an explicit weight in the target roster.
     * @param nodeId the ID of the node
     * @return whether the node has an explicit weight
     */
    public boolean targetIncludes(final long nodeId) {
        return targetNodeWeights.containsKey(nodeId);
    }

    /**
     * Returns the weight of a node in the target roster.
     * @param nodeId the ID of the node
     * @return the weight of the node
     */
    public long targetWeightOf(final long nodeId) {
        return targetNodeWeights.getOrDefault(nodeId, 0L);
    }

    /**
     * Returns the size of the target roster.
     */
    public int targetRosterSize() {
        return targetNodeWeights.size();
    }

    /**
     * Returns the number of target node ids in the source roster.
     * @return the number of target node ids in the source roster
     */
    public int numTargetNodesInSource() {
        return numTargetNodesIn(sourceNodeWeights.keySet());
    }

    /**
     * Returns the number of target node ids in a given set of node ids.
     * @param nodeIds the set of node ids
     * @return the number of target node ids in the set
     */
    public int numTargetNodesIn(@NonNull final Set<Long> nodeIds) {
        return targetNodeWeights.keySet().stream()
                .filter(nodeIds::contains)
                .mapToInt(i -> 1)
                .sum();
    }

    /**
     * Returns the weight that would constitute a strong minority of the network weight for a roster.
     *
     * @param weights the weights of the nodes in the roster
     * @return the weight required for a strong minority
     */
    public static long atLeastOneThirdOfTotal(@NonNull final Map<Long, Long> weights) {
        requireNonNull(weights);
        return atLeastOneThirdOfTotal(
                weights.values().stream().mapToLong(Long::longValue).sum());
    }

    /**
     * Returns a weight that, assuming no corruption beyond the Byzantine threshold, guarantees agreement
     * from an honest node holding at non-zero weight.
     * @param totalWeight the total weight of the network
     * @return the weight required to guarantee agreement with an honest node holding non-zero weight
     */
    public static long atLeastOneThirdOfTotal(final long totalWeight) {
        // Since aBFT is unachievable with n/3 malicious weight, using the conclusion of n/3 weight
        // ensures it the conclusion overlaps with the weight held by at least one honest node
        return (totalWeight + 2) / 3;
    }

    /**
     * Returns the weight that, assuming no corruption beyond the Byzantine threshold, guarantees sufficient
     * honest nodes to reach agreement backed by at least 1/3 of the total network weight.
     * @param weights the weights of the nodes in the roster
     * @return the weight required for consensus progress even with maximum Byzantine faults
     */
    public static long moreThanTwoThirdsOfTotal(@NonNull final Map<Long, Long> weights) {
        requireNonNull(weights);
        return moreThanTwoThirdsOfTotal(
                weights.values().stream().mapToLong(Long::longValue).sum());
    }

    /**
     * Returns the weight that, assuming no corruption beyond the Byzantine threshold, guarantees sufficient
     * honest nodes to reach agreement backed by at least 1/3 of the total network weight.
     * @param totalWeight the total weight of the network
     * @return the weight required for consensus progress even with maximum Byzantine faults
     */
    public static long moreThanTwoThirdsOfTotal(final long totalWeight) {
        // Calculate (2 * totalWeight) / 3 + 1 with BigInteger
        return BigInteger.valueOf(totalWeight)
                .multiply(BigInteger.TWO)
                .divide(BigInteger.valueOf(3))
                .add(BigInteger.ONE)
                .longValueExact();
    }
}
