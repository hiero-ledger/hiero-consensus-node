// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Generates weights for nodes using a Gaussian distribution.
 */
public class GaussianWeightGenerator implements WeightGenerator {
    private final long averageWeight;
    private final long weightStandardDeviation;

    /**
     * Creates a new Gaussian weight generator.
     *
     * @param averageWeight          the average weight
     * @param weightStandardDeviation the standard deviation of the weight
     */
    public GaussianWeightGenerator(final long averageWeight, final long weightStandardDeviation) {
        this.averageWeight = averageWeight;
        this.weightStandardDeviation = weightStandardDeviation;
    }

    @Override
    public List<Long> getWeights(final long seed, final int numberOfNodes) {
        final RandomGenerator r = Randotron.create(seed);
        final List<Long> nodeWeights = new ArrayList<>(numberOfNodes);
        for (int i = 0; i < numberOfNodes; i++) {
            nodeWeights.add(Math.max(0, (long) (averageWeight + r.nextGaussian() * weightStandardDeviation)));
        }

        return nodeWeights;
    }
}
