// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.test.performance.experiments.broadcast;

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
import org.junit.jupiter.api.TestMethodOrder;

@SuppressWarnings("NewClassNamingConvention")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SlothSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class BroadcastExperiment {
    private static final Logger log = LogManager.getLogger(BroadcastExperiment.class);

    /**
     * Test broadcast enabled.
     */
    @Benchmark
    void broadcast(@NonNull final TestEnvironment env) {
        log.info("=== Broadcast Experiment: enabled ===");
        runBenchmark(env, "broadcast", BenchmarkParameters.defaults(), network -> {
            network.withConfigValue("broadcast.enableBroadcast", true);
        });
    }
}
