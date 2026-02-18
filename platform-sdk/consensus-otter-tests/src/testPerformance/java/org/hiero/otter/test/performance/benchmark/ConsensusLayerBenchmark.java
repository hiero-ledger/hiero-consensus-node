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
import org.hiero.otter.fixtures.specs.ContainerSpecs;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.test.performance.benchmark.fixtures.BenchmarkServiceLogParser;
import org.hiero.otter.test.performance.benchmark.fixtures.LoadThrottler;
import org.hiero.otter.test.performance.benchmark.fixtures.MeasurementsCollector;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Performance benchmark test that measures consensus layer latency.
 */
@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConsensusLayerBenchmark {

    private static final Logger log = LogManager.getLogger(ConsensusLayerBenchmark.class);

    /**
     * Record holding benchmark execution parameters.
     *
     * @param numberOfNodes the number of nodes in the network
     * @param warmupCount the number of warmup transactions
     * @param transactionCount the number of benchmark transactions
     * @param maxTps the maximum transactions per second rate
     * @param stabilizationTime time to wait for network stabilization (seconds)
     * @param warmupTime time to wait after warmup transactions (seconds)
     * @param collectionTime time to wait for transaction collection (seconds)
     */
    public record BenchmarkParameters(
            int numberOfNodes,
            int warmupCount,
            int transactionCount,
            int maxTps,
            long stabilizationTime,
            long warmupTime,
            long collectionTime) {

        /**
         * Creates default benchmark parameters.
         */
        public static BenchmarkParameters defaults() {
            return new BenchmarkParameters(4, 1000, 1000, 20, 3L, 5L, 10L);
        }
    }

    /**
     * Baseline benchmark - default settings (RSA, maxOtherParents=1, antiSelfishnessFactor=10, maxCreationRate=20).
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(1)
    void benchmarkBaseline(@NonNull final TestEnvironment env) {
        log.info("=== BASELINE BENCHMARK (defaults) ===");
        runBenchmark(env, "benchmarkBaseline", BenchmarkParameters.defaults(), network -> {
            // No config changes - use defaults
        });
    }

    /**
     * Common benchmark execution logic.
     *
     * @param env the test environment
     * @param configName name of the configuration being tested
     * @param params benchmark execution parameters
     * @param networkConfigurator function to configure the network before adding nodes
     */
    public static void runBenchmark(
            @NonNull final TestEnvironment env,
            @NonNull final String configName,
            @NonNull final BenchmarkParameters params,
            @NonNull final NetworkConfigurator networkConfigurator) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final AtomicLong nonceGenerator = new AtomicLong(0);

        // Enable the BenchmarkService
        network.withConfigValue("event.services", "org.hiero.otter.fixtures.app.services.benchmark.BenchmarkService");

        network.addNodes(params.numberOfNodes());

        // Apply test-specific configuration
        networkConfigurator.configure(network);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        log.info("[{}] Starting network with {} nodes...", configName, params.numberOfNodes());
        network.start();

        // Wait for network to stabilize
        timeManager.waitFor(Duration.ofSeconds(params.stabilizationTime()));
        log.info("[{}] Network stabilized", configName);

        // Warm-up phase: submit empty transactions to warm up all nodes
        log.info(
                "[{}] Starting warm-up phase: submitting {} empty transactions across all nodes...",
                configName,
                params.warmupCount());
        final List<Node> nodes = network.nodes();
        for (int i = 0; i < params.warmupCount(); i++) {
            final Node targetNode = nodes.get(i % nodes.size());
            targetNode.submitTransaction(TransactionFactory.createEmptyTransaction(nonceGenerator.incrementAndGet()));
        }

        // Wait for warm-up to complete
        timeManager.waitFor(Duration.ofSeconds(params.warmupTime()));
        log.info("[{}] Warm-up phase complete", configName);

        log.info(
                "[{}] Starting benchmark: It will take approximately {}s submitting {} transactions at a rate of {} ops/s...",
                configName,
                params.transactionCount() / params.maxTps(),
                params.transactionCount(),
                params.maxTps());

        final LoadThrottler throttler = new LoadThrottler(
                env, () -> createBenchmarkTransaction(nonceGenerator.incrementAndGet(), timeManager.now()));
        throttler.submitWithRate(params.transactionCount(), params.maxTps());
        // Wait for all transactions to be processed
        timeManager.waitFor(Duration.ofSeconds(params.collectionTime()));
        log.info("[{}] Benchmark transactions submitted, collecting results...", configName);
        // Validations
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // Collect measurements from all nodes' logs and print the benchmark report
        final MeasurementsCollector collector = new MeasurementsCollector();
        parseFromLogs(network.newLogResults(), BenchmarkServiceLogParser::parseMeasurement, collector::addEntry);
        // Make sure the benchmark run is valid
        assertEquals(
                params.transactionCount() * params.numberOfNodes(),
                collector.computeStatistics().totalMeasurements(),
                "The benchmark is invalid as some of the transactions sent were not measured");
        final String report = collector.generateReport();
        log.info("[{}] Benchmark complete. Results:\n {}", configName, report);
        System.out.println("\n=== " + configName + " RESULTS ===");
        System.out.println(report);
    }

    /**
     * Functional interface for configuring a network before benchmarking.
     */
    @FunctionalInterface
    public interface NetworkConfigurator {
        void configure(@NonNull Network network);
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
