// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.test.performance.experiments.maxotherparents;

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
 * Experiment testing the effect of maxOtherParents configuration on consensus latency.
 */
@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SlothSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class MaxOtherParentsExperiment {

    private static final Logger log = LogManager.getLogger(MaxOtherParentsExperiment.class);
    public static final BenchmarkParameters DEFAULTS = BenchmarkParameters.defaults();

    /**
     * Test maxOtherParents=2.
     */
    @Benchmark
    @Order(1)
    void maxOtherParentsHalf(@NonNull final TestEnvironment env) {
        log.info("=== MaxOtherParents Experiment: maxOtherParents=half ===");
        runBenchmark(env, "maxOtherParentsHalf", network -> {
            network.withConfigValue("event.creation.maxOtherParents", DEFAULTS.numberOfNodes() / 2);
        });
    }

    @Benchmark
    @Order(2)
    void maxOtherParentsTwoThirdsPlusOne(@NonNull final TestEnvironment env) {
        log.info("=== MaxOtherParents Experiment: maxOtherParents=2/3+1 ===");
        runBenchmark(env, "maxOtherParentsTwoThirdsPlusOne", network -> {
            network.withConfigValue("event.creation.maxOtherParents", 2 * DEFAULTS.numberOfNodes() / 3 + 1);
        });
    }

    /**
     * Test maxOtherParents=4 (all nodes).
     */
    @Benchmark
    @Order(3)
    void maxOtherParentsAll(@NonNull final TestEnvironment env) {
        log.info("=== MaxOtherParents Experiment: maxOtherParents=All ===");
        runBenchmark(env, "maxOtherParentsAll", network -> {
            network.withConfigValue("event.creation.maxOtherParents", DEFAULTS.numberOfNodes());
        });
    }
}
