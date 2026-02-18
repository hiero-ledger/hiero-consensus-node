// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.experiments.antiselfishness;

import static org.hiero.otter.test.performance.benchmark.ConsensusLayerBenchmark.runBenchmark;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.specs.ContainerSpecs;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.test.performance.benchmark.ConsensusLayerBenchmark.BenchmarkParameters;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Experiment testing the effect of antiSelfishnessFactor configuration on consensus latency.
 */
@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AntiSelfishnessExperiment {

    private static final Logger log = LogManager.getLogger(AntiSelfishnessExperiment.class);

    /**
     * Test antiSelfishnessFactor=8.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(1)
    void antiSelfishnessFactor8(@NonNull final TestEnvironment env) {
        log.info("=== AntiSelfishness Experiment: antiSelfishnessFactor=8 ===");
        runBenchmark(env, "antiSelfishnessFactor8", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.antiSelfishnessFactor", 8);
        });
    }

    /**
     * Test antiSelfishnessFactor=5.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(2)
    void antiSelfishnessFactor5(@NonNull final TestEnvironment env) {
        log.info("=== AntiSelfishness Experiment: antiSelfishnessFactor=5 ===");
        runBenchmark(env, "antiSelfishnessFactor5", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.antiSelfishnessFactor", 5);
        });
    }
}
