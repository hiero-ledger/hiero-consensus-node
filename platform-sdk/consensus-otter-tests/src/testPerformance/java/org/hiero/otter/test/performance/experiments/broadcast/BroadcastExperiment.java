// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.experiments.broadcast;

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
import org.junit.jupiter.api.TestMethodOrder;

@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@OtterSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class BroadcastExperiment {
    private static final Logger log = LogManager.getLogger(BroadcastExperiment.class);

    /**
     * Test broadcast enabled.
     */
    @OtterTest
    void broadcast(@NonNull final TestEnvironment env) {
        log.info("=== Broadcast Experiment: enabled ===");
        runBenchmark(env, "broadcast", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("broadcast.enableBroadcast", true);
        });
    }
}
