// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.transactions.BenchmarkTransaction;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.test.performance.benchmark.fixtures.BenchmarkServiceLogParser;
import org.hiero.otter.test.performance.benchmark.fixtures.MeasurementsCollector;
import org.hiero.otter.test.performance.benchmark.fixtures.MeasurementsCollector.Measurement;

/**
 * Performance benchmark test that measures consensus layer latency.
 */
public class BenchmarkTest {

    private static final Logger log = LogManager.getLogger(BenchmarkTest.class);

    private static final AtomicLong NONCE_GENERATOR = new AtomicLong(0);
    private static final int TRANSACTION_COUNT = 10000;
    // Setup simulation with 4 nodes
    public static final int NUMBER_OF_NODES = 4;

    /**
     * Benchmark test that runs a network with 4 nodes and submits benchmark transactions.
     * The BenchmarkService logs the latency for each transaction.
     *
     * uses {@link org.hiero.otter.test.performance.benchmark.fixtures.BenchmarkService}
     *
     * @param env the test environment for this test
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    void testBenchmarkLatency(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Enable the BenchmarkService
        network.withConfigValue(
                "event.services", "org.hiero.otter.test.performance.benchmark.fixtures.BenchmarkService");

        network.addNodes(NUMBER_OF_NODES);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        network.start();

        // Wait for network to stabilize
        timeManager.waitFor(Duration.ofSeconds(3L));

        // Submit benchmark transactions
        for (int i = 0; i < TRANSACTION_COUNT; i++) {
            final OtterTransaction tx =
                    createBenchmarkTransaction(NONCE_GENERATOR.incrementAndGet(), System.currentTimeMillis());
            network.submitTransaction(tx);
        }

        // Wait for all transactions to be processed
        timeManager.waitFor(Duration.ofSeconds(10L));

        // Validations
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // Collect measurements from all nodes' logs and print the benchmark report
        final MeasurementsCollector collector = new MeasurementsCollector();
        BenchmarkServiceLogParser.parseFromLogs(network.newLogResults(), Measurement::parse, collector::addEntry);
        log.info(collector.generateReport());
    }

    /**
     * Creates a new benchmark transaction with the specified submission timestamp.
     *
     * @param nonce the nonce for the benchmark transaction
     * @param submissionTimeMillis the submission timestamp in epoch milliseconds
     * @return a benchmark transaction
     */
    @NonNull
    public static OtterTransaction createBenchmarkTransaction(final long nonce, final long submissionTimeMillis) {
        final BenchmarkTransaction benchmarkTransaction = BenchmarkTransaction.newBuilder()
                .setSubmissionTimeMillis(submissionTimeMillis)
                .build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setBenchmarkTransaction(benchmarkTransaction)
                .build();
    }
}
