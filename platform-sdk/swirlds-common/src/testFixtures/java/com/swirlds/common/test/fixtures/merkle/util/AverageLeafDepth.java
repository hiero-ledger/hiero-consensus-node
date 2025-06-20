// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

/**
 * Utility container class for measuring average leaf depth of a merkle tree.
 */
class AverageLeafDepth {
    private int numberOfLeaves;
    /**
     * The sum total of all of the depths of all of the leaves.
     */
    private int totalDepth;

    public AverageLeafDepth() {
        this.numberOfLeaves = 0;
        this.totalDepth = 0;
    }

    public void addLeaves(int numberOfLeaves) {
        this.numberOfLeaves += numberOfLeaves;
    }

    public void addDepth(int totalDepth) {
        this.totalDepth += totalDepth;
    }

    public void add(AverageLeafDepth childDepth) {
        numberOfLeaves += childDepth.numberOfLeaves;
        totalDepth += childDepth.totalDepth;
    }

    public double getAverageDepth() {
        return numberOfLeaves == 0 ? 0 : (((double) totalDepth) / ((double) numberOfLeaves));
    }
}
