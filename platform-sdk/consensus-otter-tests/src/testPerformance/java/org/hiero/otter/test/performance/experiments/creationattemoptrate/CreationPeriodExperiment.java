// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.experiments.creationattemoptrate;

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
public class CreationPeriodExperiment {
    private static final Logger log = LogManager.getLogger(CreationPeriodExperiment.class);

    /**
     * Test creationPeriod=1ms.
     */
    @OtterTest
    @Order(1)
    void creationPeriod(@NonNull final TestEnvironment env) {
        log.info("=== CreationPeriod Experiment: period=1ms ===");
        runBenchmark(env, "creationPeriod", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.period", "1ms");
        });
    }

    /**
     * Test creationFrequency=1ms.
     */
    @OtterTest
    @Order(2)
    void creationPeriod500us(@NonNull final TestEnvironment env) {
        log.info("=== CreationPeriod Experiment: frequency=500us ===");
        runBenchmark(env, "creationPeriod500us", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("event.creation.period", "500us");
        });
    }
}
