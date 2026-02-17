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
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
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

    private static final int WARMUP_COUNT = 1000;
    private static final int TRANSACTION_COUNT = 1000;
    private static final int MAX_TPS = 20;
    // Setup simulation with 4 nodes
    public static final int NUMBER_OF_NODES = 4;

    /**
     * Baseline benchmark - default settings (RSA, maxOtherParents=1, antiSelfishnessFactor=10, maxCreationRate=20).
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(1)
    void benchmarkBaseline(@NonNull final TestEnvironment env) {
        log.info("=== BASELINE BENCHMARK (defaults) ===");
        runBenchmark(env, "benchmarkBaseline", network -> {
            // No config changes - use defaults
        });
    }

    /**
     * Test maxOtherParents=2 in isolation.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(2)
    void benchmarkMaxOtherParents2(@NonNull final TestEnvironment env) {
        log.info("=== BENCHMARK: maxOtherParents=2 ===");
        runBenchmark(env, "benchmarkMaxOtherParents2", network -> {
            network.withConfigValue("event.creation.maxOtherParents", 2);
        });
    }

    /**
     * Test maxOtherParents=3 in isolation.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(3)
    void benchmarkMaxOtherParentsAll(@NonNull final TestEnvironment env) {
        log.info("=== BENCHMARK: maxOtherParents={} ===", NUMBER_OF_NODES);
        runBenchmark(env, "benchmarkMaxOtherParentsAll", network -> {
            network.withConfigValue("event.creation.maxOtherParents", NUMBER_OF_NODES);
        });
    }

    /**
     * Test antiSelfishnessFactor=5 in isolation.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(4)
    void benchmarkAntiSelfishness5(@NonNull final TestEnvironment env) {
        log.info("=== BENCHMARK: antiSelfishnessFactor=8 ===");
        runBenchmark(env, "benchmarkAntiSelfishness5", network -> {
            network.withConfigValue("event.creation.antiSelfishnessFactor", 8);
        });
    }

    /**
     * Test maxCreationRate=15 in isolation.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(5)
    void benchmarkMaxCreationRate100(@NonNull final TestEnvironment env) {
        log.info("=== BENCHMARK: maxCreationRate=100 ===");
        runBenchmark(env, "benchmarkMaxCreationRate100", network -> {
            network.withConfigValue("event.creation.maxCreationRate", 100);
        });
    }

    /**
     * Test ED25519 signature scheme in isolation.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(6)
    void benchmarkED25519(@NonNull final TestEnvironment env) {
        log.info("=== BENCHMARK: ED25519 signature scheme ===");
        runBenchmark(env, "benchmarkED25519", network -> {
            // Override signature scheme to ED25519 for all nodes
            final SecureRandom secureRandom;
            try {
                secureRandom = SecureRandom.getInstanceStrong();
                network.nodes().forEach(node -> {
                    try {
                        node.keysAndCerts(KeysAndCertsGenerator.generate(
                                node.selfId(), SigningSchema.ED25519, secureRandom, secureRandom));
                    } catch (final NoSuchAlgorithmException | NoSuchProviderException | KeyGeneratingException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (final NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Test all optimizations combined.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(7)
    void benchmarkAllOptimizations(@NonNull final TestEnvironment env) {
        log.info("=== BENCHMARK: ALL OPTIMIZATIONS ===");
        runBenchmark(env, "benchmarkAllOptimizations", network -> {
            // Apply all config optimizations
            network.withConfigValue("event.creation.maxOtherParents", NUMBER_OF_NODES);
            network.withConfigValue("event.creation.antiSelfishnessFactor", 8);
            network.withConfigValue("event.creation.maxCreationRate", 100);

            // Use ED25519 for faster signing
            final SecureRandom secureRandom;
            try {
                secureRandom = SecureRandom.getInstanceStrong();
                network.nodes().forEach(node -> {
                    try {
                        node.keysAndCerts(KeysAndCertsGenerator.generate(
                                node.selfId(), SigningSchema.ED25519, secureRandom, secureRandom));
                    } catch (final NoSuchAlgorithmException | NoSuchProviderException | KeyGeneratingException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (final NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Common benchmark execution logic.
     *
     * @param env the test environment
     * @param configName name of the configuration being tested
     * @param networkConfigurator function to configure the network before adding nodes
     */
    private void runBenchmark(
            @NonNull final TestEnvironment env,
            @NonNull final String configName,
            @NonNull final NetworkConfigurator networkConfigurator) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final AtomicLong nonceGenerator = new AtomicLong(0);

        // Enable the BenchmarkService
        network.withConfigValue("event.services", "org.hiero.otter.fixtures.app.services.benchmark.BenchmarkService");

        network.addNodes(NUMBER_OF_NODES);

        // Apply test-specific configuration
        networkConfigurator.configure(network);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        log.info("[{}] Starting network with {} nodes...", configName, NUMBER_OF_NODES);
        network.start();

        // Wait for network to stabilize
        timeManager.waitFor(Duration.ofSeconds(3L));
        log.info("[{}] Network stabilized", configName);

        // Warm-up phase: submit empty transactions to warm up all nodes
        log.info(
                "[{}] Starting warm-up phase: submitting {} empty transactions across all nodes...",
                configName,
                WARMUP_COUNT);
        final List<Node> nodes = network.nodes();
        for (int i = 0; i < WARMUP_COUNT; i++) {
            final Node targetNode = nodes.get(i % nodes.size());
            targetNode.submitTransaction(TransactionFactory.createEmptyTransaction(nonceGenerator.incrementAndGet()));
        }

        // Wait for warm-up to complete
        timeManager.waitFor(Duration.ofSeconds(5L));
        log.info("[{}] Warm-up phase complete", configName);

        log.info(
                "[{}] Starting benchmark: It will take approximately {}s submitting {} transactions at a rate of {} ops/s...",
                configName,
                TRANSACTION_COUNT / MAX_TPS,
                TRANSACTION_COUNT,
                MAX_TPS);

        final LoadThrottler throttler = new LoadThrottler(
                env, () -> createBenchmarkTransaction(nonceGenerator.incrementAndGet(), timeManager.now()));
        throttler.submitWithRate(TRANSACTION_COUNT, MAX_TPS);
        // Wait for all transactions to be processed
        timeManager.waitFor(Duration.ofSeconds(10L));
        log.info("[{}] Benchmark transactions submitted, collecting results...", configName);
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
        final String report = collector.generateReport();
        log.info("[{}] Benchmark complete. Results:\n {}", configName, report);
        System.out.println("\n=== " + configName + " RESULTS ===");
        System.out.println(report);
    }

    /**
     * Functional interface for configuring a network before benchmarking.
     */
    @FunctionalInterface
    private interface NetworkConfigurator {
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
