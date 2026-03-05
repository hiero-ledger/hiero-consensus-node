// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.experiments.instrumentation;

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

@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@OtterSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class InstrumentationExperiment {
    private static final Logger log = LogManager.getLogger(InstrumentationExperiment.class);

    /**
     * Test Customize eventIntake
     */
    @OtterTest
    @Order(1)
    void concurrentIntake(@NonNull final TestEnvironment env) {
        log.info("=== Instrumentation Experiment: eventIntake=org.hiero.consensus.event.intake.concurrent ===");
        runBenchmark(env, "concurrentIntake", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("modules.eventIntake", "org.hiero.consensus.event.intake.concurrent");
        });
    }

}
