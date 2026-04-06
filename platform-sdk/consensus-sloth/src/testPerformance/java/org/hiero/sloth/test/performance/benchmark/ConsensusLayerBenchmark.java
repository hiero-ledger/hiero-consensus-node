// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.test.performance.benchmark;

import static org.hiero.sloth.test.performance.benchmark.fixtures.BenchmarkServiceLogParser.parseFromLogs;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.sloth.fixtures.Benchmark;
import org.hiero.sloth.fixtures.Network;
import org.hiero.sloth.fixtures.Node;
import org.hiero.sloth.fixtures.ProfilerEvent;
import org.hiero.sloth.fixtures.SlothTransactionType;
import org.hiero.sloth.fixtures.TestEnvironment;
import org.hiero.sloth.fixtures.TimeManager;
import org.hiero.sloth.fixtures.specs.ContainerSpecs;
import org.hiero.sloth.fixtures.specs.SlothSpecs;
import org.hiero.sloth.test.performance.benchmark.fixtures.BenchmarkServiceLogParser;
import org.hiero.sloth.test.performance.benchmark.fixtures.MeasurementsCollector;
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
     * @param numberOfNodes     the number of nodes in the network
     * @param tps               total network transactions per second (distributed evenly across nodes)
     * @param stabilizationTime time to wait for network stabilization (seconds)
     * @param warmupTime        duration of the warmup generation phase (seconds)
     * @param benchmarkTime     duration of the benchmark generation phase (seconds)
     * @param collectionTime    time to wait after stopping generation for results to propagate (seconds)
     */
    @ConfigData("sloth")
    public record BenchmarkParameters(
            @ConfigProperty(defaultValue = "4") int numberOfNodes,
            @ConfigProperty(defaultValue = "20") int tps,
            @ConfigProperty(defaultValue = "3s") @NonNull Duration stabilizationTime,
            @ConfigProperty(defaultValue = "5s") @NonNull Duration warmupTime,
            @ConfigProperty(defaultValue = "50s") @NonNull Duration benchmarkTime,
            @ConfigProperty(defaultValue = "10s") @NonNull Duration collectionTime) {}

    /**
     * Baseline benchmark - default settings (RSA, maxOtherParents=1, antiSelfishnessFactor=10, maxCreationRate=20).
     */
    @Benchmark
    @SlothSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(1)
    void benchmarkBaseline(@NonNull final TestEnvironment env) {
        log.info("=== BASELINE BENCHMARK (defaults) ===");
        runBenchmark(env, "benchmarkBaseline", (_, _) -> {
            // No config changes - use defaults
        });
    }

    /**
     * Common benchmark execution logic.
     *
     * <p>Transactions are generated inside each node's execution layer rather than submitted by the
     * test controller.  The controller tells each node to start/stop generating at the required rate
     * via GRPC, and collects latency measurements from the nodes' logs afterwards.
     *
     * @param env the test environment
     * @param configName name of the configuration being tested
     * @param networkConfigurator function to configure the network before adding nodes
     */
    public static void runBenchmark(
            @NonNull final TestEnvironment env,
            @NonNull final String configName,
            @NonNull final NetworkConfigurator networkConfigurator) {
        runBenchmark(env, configName, false, networkConfigurator);
    }

    /**
     * Common benchmark execution logic with optional JFR profiling.
     *
     * <p>When {@code profilingEnabled} is {@code true}, JFR profiling is started on all nodes
     * before the benchmark phase and stopped after it. The resulting {@code .jfr} files are saved
     * to each node's output directory.
     *
     * @param env the test environment
     * @param configName name of the configuration being tested
     * @param profilingEnabled whether to enable JFR profiling during the benchmark phase
     * @param networkConfigurator function to configure the network before adding nodes
     */
    public static void runBenchmark(
            @NonNull final TestEnvironment env,
            @NonNull final String configName,
            final boolean profilingEnabled,
            @NonNull final NetworkConfigurator networkConfigurator) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network
                // Disable prometheus overhead
                .withConfigValue("prometheus.endpointEnabled", false)
                // Enable the BenchmarkService
                .withConfigValue(
                "slothApp.services", "org.hiero.sloth.fixtures.app.services.benchmark.BenchmarkService");

        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(BenchmarkParameters.class)
                .withSource(SystemPropertiesConfigSource.getInstance())
                .build();
        final BenchmarkParameters params = configuration.getConfigData(BenchmarkParameters.class);

        network.addNodes(params.numberOfNodes());

        // Apply test-specific configuration
        networkConfigurator.configure(network, params);

        log.info("[{}] Starting network with {} nodes...", configName, params.numberOfNodes());
        network.start();

        // Wait for network to stabilize
        timeManager.waitFor(params.stabilizationTime());
        log.info("[{}] Network stabilized", configName);

        // Per-node TPS: spread the total network TPS evenly across all nodes.
        final List<Node> nodes = network.nodes();
        final double perNodeTps = Math.max(1.0, ((double) params.tps()) / nodes.size());

        // Warm-up phase: generate empty transactions on each node for warmupTime seconds.
        log.info(
                "[{}] Starting warm-up phase: generating empty transactions at {} TPS per node for {}s...",
                configName,
                perNodeTps,
                params.warmupTime());
        nodes.parallelStream().forEach(node -> node.startTransactionGeneration(perNodeTps, SlothTransactionType.EMPTY));
        timeManager.waitFor(params.warmupTime());
        nodes.parallelStream().forEach(Node::stopTransactionGeneration);
        log.info("[{}] Warm-up phase complete", configName);

        // Start profiling on all nodes before the benchmark phase
        if (profilingEnabled) {
            log.info("[{}] Starting JFR profiling on all nodes...", configName);
            nodes.parallelStream()
                    .forEach(node -> node.startProfiling(
                            configName + "-node-" + node.selfId().id() + ".jfr",
                            ProfilerEvent.CPU,
                            ProfilerEvent.ALLOCATION));
        }

        // Benchmark phase: generate benchmark transactions on each node.
        log.info(
                "[{}] Starting benchmark: generating BENCHMARK transactions at {} TPS per node for {}s...",
                configName,
                perNodeTps,
                params.benchmarkTime());
        nodes.parallelStream()
                .forEach(node -> node.startTransactionGeneration(perNodeTps, SlothTransactionType.BENCHMARK));
        timeManager.waitFor(params.benchmarkTime());
        final long totalGenerated = nodes.parallelStream()
                .mapToLong(Node::stopTransactionGeneration)
                .sum();
        log.info("[{}] Generated {} benchmark transactions in total", configName, totalGenerated);

        // Stop profiling after the benchmark phase
        if (profilingEnabled) {
            log.info("[{}] Stopping JFR profiling on all nodes...", configName);
            nodes.parallelStream().forEach(Node::stopProfiling);
            log.info("[{}] JFR profiling stopped. Results saved to node output directories.", configName);
        }

        // Wait for all transactions to reach consensus and be logged.
        timeManager.waitFor(params.collectionTime());
        log.info("[{}] Collecting results...", configName);

        // Collect measurements from all nodes' logs and print the benchmark report.
        final MeasurementsCollector collector = new MeasurementsCollector();
        parseFromLogs(network.newLogResults(), BenchmarkServiceLogParser::parseMeasurement, collector::addEntry);

        final String report = collector.generateReport();
        log.info("[{}] Benchmark complete. Results:\n {}", configName, report);
        System.out.println("\n=== " + configName + " RESULTS ===");
        System.out.println(report);

        // Every generated transaction is handled by only by the node that generated it
        assertEquals(
                totalGenerated,
                collector.computeStatistics().totalMeasurements(),
                "The benchmark is invalid as some of the transactions generated were not measured");
    }

    /**
     * Functional interface for configuring a network before benchmarking.
     */
    @FunctionalInterface
    public interface NetworkConfigurator {
        void configure(@NonNull Network network, @NonNull final BenchmarkParameters params);
    }
}
