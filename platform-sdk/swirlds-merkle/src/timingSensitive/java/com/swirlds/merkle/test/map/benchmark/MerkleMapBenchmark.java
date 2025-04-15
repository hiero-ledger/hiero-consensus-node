// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.benchmark.Benchmark;
import com.swirlds.common.test.fixtures.benchmark.BenchmarkConfiguration;
import com.swirlds.common.test.fixtures.benchmark.BenchmarkOperation;
import com.swirlds.common.test.fixtures.benchmark.BenchmarkStatistic;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkAccount;
import com.swirlds.merkle.test.fixtures.map.benchmark.BenchmarkKey;
import com.swirlds.merkle.test.fixtures.map.benchmark.MerkleMapBenchmarkMetadata;
import com.swirlds.merkle.test.fixtures.map.benchmark.MerkleMapBenchmarkUtils;
import com.swirlds.merkle.test.fixtures.map.benchmark.operations.CreateAccountOperation;
import com.swirlds.merkle.test.fixtures.map.benchmark.operations.DeleteAccountOperation;
import com.swirlds.merkle.test.fixtures.map.benchmark.operations.ReadBalanceOperation;
import com.swirlds.merkle.test.fixtures.map.benchmark.operations.TransferOperation;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@DisplayName("MerkleMap Benchmark")
class MerkleMapBenchmark {

    private static final Random random = new Random();

    private static final int DATA_SIZE = 100;

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.merkle.map");
        registry.registerConstructables("com.swirlds.merkle.tree");
    }

    @Test
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Standard MerkleMap Benchmark")
    void standardMerkleMapBenchmark() throws InterruptedException {

        final int initialStateSize = 2_000_000;
        System.out.println("Generating initial state with " + initialStateSize + " accounts");

        final MerkleMapBenchmarkMetadata metadata = new MerkleMapBenchmarkMetadata();
        final MerkleMap<BenchmarkKey, BenchmarkAccount> merkleState = MerkleMapBenchmarkUtils.generateInitialState(
                random, DATA_SIZE, metadata, initialStateSize, BenchmarkAccount::new);

        final double createAccountWeight = 0.5;
        final double deleteAccountWeight = createAccountWeight;
        final double transferWeight = 99.0;

        // To make it easier to reason about, ensure that all weights sum to 100
        final double sum = createAccountWeight + deleteAccountWeight + transferWeight;
        assertEquals(100.0, sum, "weights should equal 100");

        final List<BenchmarkOperation<MerkleMap<BenchmarkKey, BenchmarkAccount>, MerkleMapBenchmarkMetadata>>
                mutableOperations = List.of(
                        new CreateAccountOperation<>(createAccountWeight, DATA_SIZE, BenchmarkAccount::new),
                        new DeleteAccountOperation<>(deleteAccountWeight),
                        new TransferOperation<>(transferWeight));

        final List<BenchmarkOperation<MerkleMap<BenchmarkKey, BenchmarkAccount>, MerkleMapBenchmarkMetadata>>
                immutableOperations = List.of(new ReadBalanceOperation<>(100));

        final Benchmark<MerkleMap<BenchmarkKey, BenchmarkAccount>, MerkleMapBenchmarkMetadata> benchmark =
                new BenchmarkConfiguration<MerkleMap<BenchmarkKey, BenchmarkAccount>, MerkleMapBenchmarkMetadata>()
                        .setInitialState(merkleState)
                        .setBenchmarkMetadata(metadata)
                        .setMutableStateOperations(mutableOperations)
                        .setImmutableStateOperations(immutableOperations)
                        .setTestDuration(Duration.ofSeconds(60))
                        .build();

        benchmark.printSettings();

        System.out.println("\nStarting benchmark");

        Collection<BenchmarkStatistic> statistics = benchmark.run();

        for (final BenchmarkStatistic statistic : statistics) {
            System.out.println(statistic);
        }
    }
}
