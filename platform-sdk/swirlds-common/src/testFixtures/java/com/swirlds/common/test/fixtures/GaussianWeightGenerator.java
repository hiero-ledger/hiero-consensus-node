// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Generates weights for nodes using a Gaussian distribution.
 */
public class GaussianWeightGenerator implements WeightGenerator {
    private final long networkWeight;
    private final long averageNodeWeight;
    private final long weightStandardDeviation;

    /**
     * Creates a new Gaussian weight generator.
     *
     * @param networkWeight the total network weight
     * @param weightStandardDeviation the standard deviation of the weight
     */
    private GaussianWeightGenerator(
            final long networkWeight, final long averageNodeWeight, final long weightStandardDeviation) {
        this.networkWeight = networkWeight;
        this.averageNodeWeight = averageNodeWeight;
        this.weightStandardDeviation = weightStandardDeviation;
    }

    /**
     * Creates a new Gaussian weight generator with the given network weight and standard deviation.
     *
     * @param networkWeight the total network weight
     * @param weightStandardDeviation the standard deviation of the weight
     * @return a new Gaussian weight generator
     */
    public static GaussianWeightGenerator withNetworkWeight(
            final long networkWeight, final long weightStandardDeviation) {
        return new GaussianWeightGenerator(networkWeight, -1, weightStandardDeviation);
    }

    /**
     * Creates a new Gaussian weight generator with the given average node weight and standard deviation.
     *
     * @param averageNodeWeight the average weight of each node
     * @param weightStandardDeviation the standard deviation of the weight
     * @return a new Gaussian weight generator
     */
    public static GaussianWeightGenerator withAverageNodeWeight(
            final long averageNodeWeight, final long weightStandardDeviation) {
        return new GaussianWeightGenerator(-1, averageNodeWeight, weightStandardDeviation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Long> getWeights(final long seed, final int numberOfNodes) {
        final long averageWeight = averageNodeWeight == -1 ? this.networkWeight / numberOfNodes : averageNodeWeight;
        final RandomGenerator r = Randotron.create(seed);
        final List<Long> nodeWeights = new ArrayList<>(numberOfNodes);
        for (int i = 0; i < numberOfNodes; i++) {
            nodeWeights.add(Math.max(0, (long) (averageWeight + r.nextGaussian() * weightStandardDeviation)));
        }

        return nodeWeights;
    }
}
