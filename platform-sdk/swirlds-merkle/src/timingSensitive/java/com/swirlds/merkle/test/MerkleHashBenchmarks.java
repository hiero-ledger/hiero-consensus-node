// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.common.utility.StopWatch;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Benchmarks for merkle hashing.
 */
@DisplayName("Merkle Hash Benchmarks")
public class MerkleHashBenchmarks {

    private static MerkleCryptography cryptography;

    @BeforeAll
    public static void setUp() {
        cryptography = TestMerkleCryptoFactory.getInstance();
    }

    /**
     * Hash a merkle dag and return the time in microseconds.
     */
    private long timeHash(DummyMerkleNode root, boolean sync) throws ExecutionException, InterruptedException {
        StopWatch sw = new StopWatch();
        sw.start();
        if (sync) {
            cryptography.digestTreeSync(root);
        } else {
            cryptography.digestTreeAsync(root).get();
        }
        sw.stop();

        return sw.getTime(TimeUnit.MICROSECONDS);
    }

    private double benchmarkHashesWithParameters(
            long seed,
            double numberOfLeavesAverage,
            double numberOfLeavesStandardDeviation,
            double leafSizeAverage,
            double leafSizeStandardDeviation,
            double numberOfInternalNodesAverage,
            double numberOfInternalNodesStandardDeviation,
            double numberOfInternalNodesDecayFactor,
            int numberOfIterations,
            boolean sync)
            throws IOException, InterruptedException, ExecutionException {
        Random seedGenerator = new Random(seed);

        long totalTime = 0;

        double totalLeaves = 0;
        double totalNodes = 0;
        double sumOfDepths = 0;

        for (int i = 0; i < numberOfIterations; i++) {
            if (i % Math.max(numberOfIterations / 10, 1) == 0) {
                System.out.println("Hashing tree " + i + "/" + numberOfIterations);
            }
            DummyMerkleNode tree = MerkleTestUtils.generateRandomTree(
                    seedGenerator.nextLong(),
                    numberOfLeavesAverage,
                    numberOfLeavesStandardDeviation,
                    leafSizeAverage,
                    leafSizeStandardDeviation,
                    numberOfInternalNodesAverage,
                    numberOfInternalNodesStandardDeviation,
                    numberOfInternalNodesDecayFactor);
            totalTime += timeHash(tree, sync);
            totalLeaves += MerkleTestUtils.measureNumberOfLeafNodes(tree);
            totalNodes += MerkleTestUtils.measureNumberOfNodes(tree);
            sumOfDepths += MerkleTestUtils.measureTreeDepth(tree);
        }

        final double averageTime = ((double) totalTime) / numberOfIterations;

        System.out.println("Average time to hash: " + averageTime + "us");
        System.out.println("Average number of leaves: " + (totalLeaves / numberOfIterations));
        System.out.println("Average number of nodes: " + (totalNodes / numberOfIterations));
        System.out.println("Average tree depth: " + (sumOfDepths / numberOfIterations));

        return averageTime;
    }

    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Hash Small Trees")
    public void hashSmallTrees() throws IOException, InterruptedException, ExecutionException {
        System.out.println("--- Synchronous hashing ---");
        double syncTime = benchmarkHashesWithParameters(1337, 3, 1, 64, 10, 3, 1, 0.15, 1000, true);
        System.out.println("--- Asynchronous hashing ---");
        double asyncTime = benchmarkHashesWithParameters(1337, 3, 1, 64, 10, 3, 1, 0.15, 1000, false);

        System.out.println("Speedup from multithreading: " + (syncTime / asyncTime));
    }

    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Hash Large Trees")
    public void hashLargeTrees() throws IOException, InterruptedException, ExecutionException {
        System.out.println("--- Synchronous hashing ---");
        double syncTime = benchmarkHashesWithParameters(1337, 3, 1, 64, 10, 3, 1, 0.1, 100, true);
        System.out.println("--- Asynchronous hashing ---");
        double asyncTime = benchmarkHashesWithParameters(1337, 3, 1, 64, 10, 3, 1, 0.1, 100, false);

        System.out.println("Speedup from multithreading: " + (syncTime / asyncTime));
    }

    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Hash Huge Trees")
    public void hashHugeTrees() throws IOException, InterruptedException, ExecutionException {
        System.out.println("--- Synchronous hashing ---");
        double syncTime = benchmarkHashesWithParameters(1337, 3, 1, 64, 10, 3, 1, 0.08, 10, true);
        System.out.println("--- Asynchronous hashing ---");
        double asyncTime = benchmarkHashesWithParameters(1337, 3, 1, 64, 10, 3, 1, 0.08, 10, false);

        System.out.println("Speedup from multithreading: " + (syncTime / asyncTime));
    }
}
