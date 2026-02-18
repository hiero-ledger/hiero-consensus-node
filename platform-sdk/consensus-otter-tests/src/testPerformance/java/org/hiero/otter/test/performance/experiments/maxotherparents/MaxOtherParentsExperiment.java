// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.experiments.maxotherparents;

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
 * Experiment testing the effect of maxOtherParents configuration on consensus latency.
 */
@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@OtterSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class MaxOtherParentsExperiment {

    private static final Logger log = LogManager.getLogger(MaxOtherParentsExperiment.class);
    public static final BenchmarkParameters DEFAULTS = BenchmarkParameters.defaults();

    /**
     * Test maxOtherParents=2.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(1)
    void maxOtherParentsHalf(@NonNull final TestEnvironment env) {
        log.info("=== MaxOtherParents Experiment: maxOtherParents=2 ===");
        runBenchmark(env, "maxOtherParentsHalf", DEFAULTS, network -> {
            network.withConfigValue("event.creation.maxOtherParents", DEFAULTS.numberOfNodes() / 2);
        });
    }

    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ContainerSpecs(proxyEnabled = false)
    @Order(2)
    void maxOtherParentsTwoThirdsPlusOne(@NonNull final TestEnvironment env) {
        log.info("=== MaxOtherParents Experiment: maxOtherParents=2/3+1 ===");
        runBenchmark(env, "maxOtherParentsHalf", DEFAULTS, network -> {
            network.withConfigValue("event.creation.maxOtherParents", 2 * DEFAULTS.numberOfNodes() / 3 + 1);
        });
    }

    /**
     * Test maxOtherParents=4 (all nodes).
     */
    @OtterTest
    @Order(3)
    void maxOtherParentsAll(@NonNull final TestEnvironment env) {
        log.info("=== MaxOtherParents Experiment: maxOtherParents=All ===");
        runBenchmark(env, "maxOtherParents4", DEFAULTS, network -> {
            network.withConfigValue("event.creation.maxOtherParents", DEFAULTS.numberOfNodes());
        });
    }
}
