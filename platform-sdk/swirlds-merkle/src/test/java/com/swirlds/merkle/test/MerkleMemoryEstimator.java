// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

public class MerkleMemoryEstimator {

    double measureMemoryUsedMb() {
        double memoryUsed =
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        return memoryUsed / 1024 / 1024;
    }

    /**
     * Estimate the memory size of a merkle tree.
     */
    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Estimate Balanced Tree Size")
    void estimateBalancedTreeSize() {

        double initialMemoryUsed = measureMemoryUsedMb();

        MerkleNode node = MerkleTestUtils.generateRandomBalancedTree(1337, 20, 2, 100, 50);

        MerkleInternal root = new DummyMerkleInternal();
        root.setChild(0, node);
        root.setChild(1, new DummyMerkleLeaf("A"));
        root.setChild(2, new DummyMerkleLeaf("B"));
        root.setChild(3, new DummyMerkleLeaf("C"));
        root.setChild(4, new DummyMerkleLeaf("D"));

        double finalMemoryUsed = measureMemoryUsedMb();

        System.out.println("Initial memory used: " + initialMemoryUsed + " Mb");
        System.out.println("Final memory used: " + finalMemoryUsed + " Mb");
        System.out.println("Memory used by tree (estimated): " + (finalMemoryUsed - initialMemoryUsed) + " Mb");
    }
}
