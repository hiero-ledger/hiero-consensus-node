// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark;

import static com.swirlds.common.utility.InstantUtils.instantToMicros;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.hiero.otter.test.performance.benchmark.fixtures.BenchmarkServiceLogParser.parseFromLogs;
import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.network.transactions.BenchmarkTransaction;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.test.performance.benchmark.fixtures.BenchmarkServiceLogParser;
import org.hiero.otter.test.performance.benchmark.fixtures.LoadThrottler;
import org.hiero.otter.test.performance.benchmark.fixtures.MeasurementsCollector;

/**
 * Performance benchmark test that measures consensus layer latency.
 */
@SuppressWarnings("NewClassNamingConvention")
public class ConsensusLayerBenchmark {

    private static final Logger log = LogManager.getLogger(ConsensusLayerBenchmark.class);

    private static final int WARMUP_COUNT = 1000;
    private static final int TRANSACTION_COUNT = 1000;
    private static final int MAX_TPS = 20;
    // Setup simulation with 4 nodes
    public static final int NUMBER_OF_NODES = 2;

    /**
     * Benchmark test that runs a network with 4 nodes and submits benchmark transactions.
     * The BenchmarkService logs the latency for each transaction.
     * <p>
     * uses {@code BenchmarkService}
     *
     * @param env the test environment for this test
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    void benchmark(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final AtomicLong nonceGenerator = new AtomicLong(0);

        // Enable the BenchmarkService
        network.withConfigValue("event.services", "org.hiero.otter.fixtures.app.services.benchmark.BenchmarkService");

        network.addNodes(NUMBER_OF_NODES);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        log.info("Starting network with {} nodes...", NUMBER_OF_NODES);
        network.start();

        // Wait for network to stabilize
        timeManager.waitFor(Duration.ofSeconds(3L));
        log.info("Network stabilized");

        // Warm-up phase: submit empty transactions to warm up all nodes
        log.info("Starting warm-up phase: submitting {} empty transactions across all nodes...", WARMUP_COUNT);
        final List<Node> nodes = network.nodes();
        for (int i = 0; i < WARMUP_COUNT; i++) {
            final Node targetNode = nodes.get(i % nodes.size());
            targetNode.submitTransaction(TransactionFactory.createEmptyTransaction(nonceGenerator.incrementAndGet()));
        }

        // Wait for warm-up to complete
        timeManager.waitFor(Duration.ofSeconds(5L));
        log.info("Warm-up phase complete");

        log.info(
                "Starting benchmark: It will take approximately {}s submitting {} transactions at a rate of {} ops/s...",
                TRANSACTION_COUNT / MAX_TPS,
                TRANSACTION_COUNT,
                MAX_TPS);

        final LoadThrottler throttler = new LoadThrottler(
                env, () -> createBenchmarkTransaction(nonceGenerator.incrementAndGet(), timeManager.now()));
        throttler.submitWithRate(TRANSACTION_COUNT, MAX_TPS);
        // Wait for all transactions to be processed
        timeManager.waitFor(Duration.ofSeconds(10L));
        log.info("Benchmark transactions submitted, collecting results...");
        // Validations
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // Collect measurements from all nodes' logs and print the benchmark report
        final MeasurementsCollector collector = new MeasurementsCollector();
        parseFromLogs(network.newLogResults(), BenchmarkServiceLogParser::parseMeasurement, collector::addEntry);
        // Make sure the benchmark run is valid
        assertEquals(
                TRANSACTION_COUNT * NUMBER_OF_NODES,
                collector.computeStatistics().totalMeasurements(),
                "The benchmark is invalid as some of the transactions sent were not measured");
        log.info("Benchmark complete. Results:");
        final String report = collector.generateReport();
        log.info(report);
        System.out.println(report);
    }

    /**
     * Creates a new benchmark transaction with the specified submission timestamp.
     *
     * @param nonce the nonce for the benchmark transaction
     * @param submissionTime the submission timestamp
     * @return a benchmark transaction
     */
    @NonNull
    public static OtterTransaction createBenchmarkTransaction(final long nonce, @NonNull final Instant submissionTime) {
        final BenchmarkTransaction benchmarkTransaction = BenchmarkTransaction.newBuilder()
                .setSubmissionTimeMicros(instantToMicros(submissionTime))
                .build();
        return OtterTransaction.newBuilder()
                .setNonce(nonce)
                .setBenchmarkTransaction(benchmarkTransaction)
                .build();
    }
}
