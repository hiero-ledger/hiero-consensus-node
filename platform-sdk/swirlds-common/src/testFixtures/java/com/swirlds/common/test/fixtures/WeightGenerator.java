// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import java.util.List;

/**
 * Generates a list of node weights given a seed and number of nodes. The list of weight values must have a size equal to
 * the specified number of nodes.
 */
@FunctionalInterface
public interface WeightGenerator {

    /**
     * Generate a list of weight values.
     *
     * @param seed
     * 		the seed to use for randomization. May or may not be used depending on the implementation.
     * @param numberOfNodes
     * 		the number of nodes to generate weight for
     * @return a list of weights equal in size to {@code numberOfNodes}
     */
    List<Long> getWeights(long seed, int numberOfNodes);
}
