// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.experiments.maxcreationrate;

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
 * Experiment testing the effect of maxCreationRate configuration on consensus latency.
 */
@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@OtterSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class MaxCreationRateExperiment {

    private static final Logger log = LogManager.getLogger(MaxCreationRateExperiment.class);

    /**
     * Test maxCreationRate=50.
     */
    @OtterTest
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
    @OtterTest
    @Order(2)
    void maxCreationRate100(@NonNull final TestEnvironment env) {
        log.info("=== MaxCreationRate Experiment: maxCreationRate=100 ===");
        runBenchmark(env, "maxCreationRate100", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.maxCreationRate", 100);
        });
    }
}
