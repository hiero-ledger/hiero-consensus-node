// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.test.performance.experiments.maxcreationrate;

import static org.hiero.sloth.test.performance.benchmark.ConsensusLayerBenchmark.runBenchmark;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.sloth.fixtures.Benchmark;
import org.hiero.sloth.fixtures.TestEnvironment;
import org.hiero.sloth.fixtures.specs.ContainerSpecs;
import org.hiero.sloth.fixtures.specs.SlothSpecs;
import org.hiero.sloth.test.performance.benchmark.ConsensusLayerBenchmark.BenchmarkParameters;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Experiment testing the effect of maxCreationRate configuration on consensus latency.
 */
@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SlothSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class MaxCreationRateExperiment {

    private static final Logger log = LogManager.getLogger(MaxCreationRateExperiment.class);

    /**
     * Test maxCreationRate=50.
     */
    @Benchmark
    @Order(1)
    void maxCreationRate50(@NonNull final TestEnvironment env) {
        log.info("=== MaxCreationRate Experiment: maxCreationRate=50 ===");
        runBenchmark(env, "maxCreationRate50", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.maxCreationRate", 50);
        });
    }

    /**
     * Test maxCreationRate=100.
     */
    @Benchmark
    @Order(2)
    void maxCreationRate100(@NonNull final TestEnvironment env) {
        log.info("=== MaxCreationRate Experiment: maxCreationRate=100 ===");
        runBenchmark(env, "maxCreationRate100", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.maxCreationRate", 100);
        });
    }

    /**
     * Test maxCreationRate=100.
     */
    @Benchmark
    @Order(2)
    void maxCreationRateUnbounded(@NonNull final TestEnvironment env) {
        log.info("=== MaxCreationRate Experiment: maxCreationRate=Unbounded ===");
        runBenchmark(env, "maxCreationRateUnbounded", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.maxCreationRate", 0);
        });
    }
}
