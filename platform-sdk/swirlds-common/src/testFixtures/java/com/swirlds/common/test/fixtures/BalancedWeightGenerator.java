// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import java.util.List;

public class BalancedWeightGenerator implements WeightGenerator {

    @Override
    public List<Long> getWeights(final Long seed, final int numberOfNodes) {
        return List.of();
    }
}
