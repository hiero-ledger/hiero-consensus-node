// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.initRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

public final class WeightGenerators {

    public static final WeightGenerator BALANCED = (l, i) -> WeightGenerators.balancedNodeWeights(i);
    public static final WeightGenerator BALANCED_1000_PER_NODE =
            (l, i) -> WeightGenerators.balancedNodeWeights(i, i * 1000L);
    public static final WeightGenerator BALANCED_REAL_WEIGHT = (l, i) -> WeightGenerators.balancedNodeWeights(i, true);
    public static final WeightGenerator INCREMENTING = (l, i) -> WeightGenerators.incrementingWeight(i);
    public static final WeightGenerator SINGLE_NODE_STRONG_MINORITY =
            (l, i) -> WeightGenerators.singleNodeWithStrongMinority(i);
    public static final WeightGenerator ONE_THIRD_ZERO_WEIGHT = WeightGenerators::oneThirdNodesZeroWeight;
    public static final WeightGenerator RANDOM = WeightGenerators::randomNodeWeights;
    public static final WeightGenerator RANDOM_REAL_WEIGHT = (l, i) -> WeightGenerators.randomNodeWeights(l, i, true);
    public static final WeightGenerator GAUSSIAN = new GaussianWeightGenerator(1000, 100);

    /**
     * total weights are the same as the number of the number of tinybars in existence
     * (50 billion)*(times 100 million)
     */
    public static final long TOTAL_WEIGHTS = 50L * 1_000_000_000L * 100L * 1_000_000L;

    private static final long MINIMUM_NON_ZERO_WEIGHT = 1L;

    /** A weight value that is easy to read and makes math calculations easy. */
    private static final int EASY_TO_READ_WEIGHT = 90;

    private WeightGenerators() {}

    /**
     * Generates balanced weight values for each node, where each node has 1 weight.
     *
     * @param numberOfNodes
     * 		the number of nodes to generate weight for
     * @return a list of weight values
     */
    public static List<Long> balancedNodeWeights(final int numberOfNodes) {
        return balancedNodeWeights(numberOfNodes, numberOfNodes);
    }

    /**
     * Generates balanced weight values for each node.
     *
     * @param numberOfNodes
     * 		the number of nodes to generate weight for
     * @param useRealTotalWeight
     * 		true if the real amount of total weight should be evenly distributed amongst each node
     * @return a list of weight values
     */
    public static List<Long> balancedNodeWeights(final int numberOfNodes, final boolean useRealTotalWeight) {
        if (useRealTotalWeight) {
            return balancedNodeWeights(numberOfNodes, TOTAL_WEIGHTS);
        }
        return balancedNodeWeights(numberOfNodes);
    }

    /**
     * Generates balanced weight values for each node, where each node is assigned an equal part of the {@code total
     * weight}. If {@code totalWeight} is not evenly divisible by {@code numberOfNodes}, not all weight will be
     * distributed to maintain weight balance.
     *
     * @param numberOfNodes
     * 		the number of nodes to generate weight for
     * @param totalWeight
     * 		the total amount of weight to distribute. Note that not all weight is assigned if not evenly divisible among
     * 		the nodes.
     * @return a list of weight values
     */
    public static List<Long> balancedNodeWeights(final int numberOfNodes, final long totalWeight) {
        final long weightPerNode = totalWeight / numberOfNodes;
        return Collections.nCopies(numberOfNodes, weightPerNode);
    }

    /**
     * Generates random node weights.
     *
     * @param weightSeed
     * 		the seed to use for the random number generator
     * @param numberOfNodes
     * 		the number of nodes to generate weight for
     * @param useRealTotalWeight
     * 		if true, the real amount of total weight should be distributed. If false, a smaller, easier to read value
     * 		will be used as the maximum for each node.
     * @return a list of weight values
     */
    public static List<Long> randomNodeWeights(
            final Long weightSeed, final int numberOfNodes, final boolean useRealTotalWeight) {
        if (useRealTotalWeight) {
            return randomNodeWeights(weightSeed, numberOfNodes, TOTAL_WEIGHTS);
        }
        return randomNodeWeights(weightSeed, numberOfNodes);
    }

    /**
     * Generates a list of node weights that are pseudo-random and sum to exactly {@code totalWeight}. No node is assigned
     * more than 1/2 of the total weight. Nodes may be assigned zero weight.
     *
     * @param weightSeed
     * 		the seed to use for the random number generator
     * @param numberOfNodes
     * 		the number of nodes to generate weight for
     * @param totalWeight
     * 		the total amount of weight to distribute
     * @return a list of weight values
     */
    public static List<Long> randomNodeWeights(final long weightSeed, final int numberOfNodes, final long totalWeight) {
        final Random r = initRandom(weightSeed);
        final List<Long> weights = new ArrayList<>(numberOfNodes);
        final long halfTotalWeight = totalWeight / 2;
        final long firstNodeWeight = r.nextLong(halfTotalWeight);
        long remainingWeight = totalWeight - firstNodeWeight;
        weights.add(firstNodeWeight);
        for (int i = 1; i < numberOfNodes - 1; i++) {
            final long weight = r.nextLong(remainingWeight);
            remainingWeight -= weight;
            weights.add(weight);
        }
        weights.add(remainingWeight);
        return weights;
    }

    /**
     * Generates random weight values for each node between 1 (inclusive) and 90 (exclusive).
     *
     * @param weightSeed
     * 		the seed to use for the random number generator
     * @param numberOfNodes
     * 		the number of nodes to generate weight for
     * @return a list of weight values
     */
    public static List<Long> randomNodeWeights(final long weightSeed, final int numberOfNodes) {
        final RandomGenerator r = initRandom(weightSeed);
        final List<Long> nodeWeights = new ArrayList<>(numberOfNodes);
        for (int i = 0; i < numberOfNodes; i++) {
            nodeWeights.add(r.nextLong(MINIMUM_NON_ZERO_WEIGHT, EASY_TO_READ_WEIGHT));
        }
        return nodeWeights;
    }

    /**
     * Creates a list of node weights where 1/3 of the nodes are zero-weight and the
     * remaining nodes are assigned a random weight value between 1 and 90, inclusive.
     *
     * @return test arguments
     */
    public static List<Long> oneThirdNodesZeroWeight(final long weightSeed, final int numberOfNodes) {
        final RandomGenerator r = initRandom(weightSeed);
        final List<Long> nodeWeights = new ArrayList<>(numberOfNodes);

        for (int nodeId = 0; nodeId < numberOfNodes; nodeId++) {
            long weight = r.nextLong(MINIMUM_NON_ZERO_WEIGHT, EASY_TO_READ_WEIGHT);
            if (nodeId < (numberOfNodes / 3)) {
                weight = 0;
            }
            nodeWeights.add(weight);
        }
        return nodeWeights;
    }

    /**
     * Creates a list of node weights where a single node has a strong minority of weight and
     * the remaining weight is evenly distributed among the remaining nodes.
     *
     * @param numberOfNodes
     * 		the number of nodes to generate weight for. Minimum of 3 nodes is required.
     * @return test arguments
     */
    public static List<Long> singleNodeWithStrongMinority(final int numberOfNodes) {
        if (numberOfNodes < 3 || numberOfNodes > 61) {
            throw new IllegalArgumentException(String.format(
                    "Invalid number of nodes: %s. Valid range is 3 - 61 for this weight distribution.", numberOfNodes));
        }
        final int totalWeight = EASY_TO_READ_WEIGHT;
        final int strongMinorityWeight = totalWeight / 3;

        final List<Long> nodeWeights = new ArrayList<>(numberOfNodes);

        for (int nodeId = 0; nodeId < numberOfNodes; nodeId++) {
            long weight = (totalWeight - strongMinorityWeight) / (numberOfNodes - 1);
            if (nodeId == 0) {
                // a single node has a strong minority of weight
                weight = strongMinorityWeight;
            }
            nodeWeights.add(weight);
        }
        return nodeWeights;
    }

    /**
     * Creates a list of node weights where nodes are assigned an incrementing amount of weight.
     *
     * @return test arguments
     */
    public static List<Long> incrementingWeight(final int numberOfNodes) {
        final List<Long> nodeWeights = new ArrayList<>(numberOfNodes);

        for (int nodeId = 0; nodeId < numberOfNodes; nodeId++) {
            final long weight = nodeId + 1L;
            nodeWeights.add(weight);
        }
        return nodeWeights;
    }
}
