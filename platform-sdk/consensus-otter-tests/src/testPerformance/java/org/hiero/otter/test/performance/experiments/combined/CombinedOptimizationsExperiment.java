// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.experiments.combined;

import static org.hiero.otter.test.performance.benchmark.ConsensusLayerBenchmark.runBenchmark;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.specs.ContainerSpecs;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.test.performance.benchmark.ConsensusLayerBenchmark.BenchmarkParameters;

/**
 * Experiment testing combined optimizations for maximum performance.
 */
@SuppressWarnings("NewClassNamingConvention")
@OtterSpecs(randomNodeIds = false)
@ContainerSpecs(
        proxyEnabled = false,
        gcLogging = true,
        jvmArgs = {
            "-XX:+UseZGC",
            "-XX:+ZGenerational",
            "-XX:+AlwaysPreTouch",
            "-XX:ConcGCThreads=4",
            "-Xms16g",
            "-Xmx16g"
        })
public class CombinedOptimizationsExperiment {

    private static final Logger log = LogManager.getLogger(CombinedOptimizationsExperiment.class);
    public static final BenchmarkParameters DEFAULTS = new BenchmarkParameters(4, 1000, 4000, 20, 3L, 5L, 40L);

    /**
     * Apply all identified optimizations together.
     */
    @OtterTest
    void combinedAllOptimizations(@NonNull final TestEnvironment env) {
        log.info("=== Combined Experiment: All Optimizations ===");
        runBenchmark(env, "combinedAllOptimizations", DEFAULTS, network -> {
            // Apply all config optimizations
            network.withConfigValue("event.creation.maxOtherParents", DEFAULTS.numberOfNodes())
                    .withConfigValue("event.creation.antiSelfishnessFactor", 8)
                    .withConfigValue("event.creation.maxCreationRate", 0)
                    .withConfigValue("event.creation.period", "100us")
                    .withConfigValue("broadcast.enableBroadcast", true)
                    .withConfigValue("sync.pingPeriod", "100ms");

            // Use ED25519 for faster signing
            final SecureRandom secureRandom;
            try {
                secureRandom = SecureRandom.getInstanceStrong();
                network.nodes().forEach(node -> {
                    try {
                        node.keysAndCerts(KeysAndCertsGenerator.generate(
                                node.selfId(), SigningSchema.ED25519, secureRandom, secureRandom));
                    } catch (final NoSuchAlgorithmException | NoSuchProviderException | KeyGeneratingException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (final NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
