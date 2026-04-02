// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.test.performance.experiments.combined;

import static org.hiero.sloth.test.performance.benchmark.ConsensusLayerBenchmark.runBenchmark;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.sloth.fixtures.Benchmark;
import org.hiero.sloth.fixtures.TestEnvironment;
import org.hiero.sloth.fixtures.specs.ContainerSpecs;
import org.hiero.sloth.fixtures.specs.SlothSpecs;

/**
 * Experiment testing combined optimizations for maximum performance.
 */
@SuppressWarnings("NewClassNamingConvention")
@SlothSpecs(randomNodeIds = false)
@ContainerSpecs(
        proxyEnabled = false,
        gcLogging = true,
        jvmArgs = {
                "-XX:+UseZGC",
                "-XX:+ZGenerational",
                //"-XX:+AlwaysPreTouch",
                "-XX:ConcGCThreads=1",
                "-Xms4g",
                "-Xmx4g"
        })
public class CombinedOptimizationsExperiment {

    private static final Logger log = LogManager.getLogger(CombinedOptimizationsExperiment.class);

    /**
     * Apply all identified optimizations together.
     */
    @Benchmark
    void combinedAllOptimizations(@NonNull final TestEnvironment env) {
        log.info("=== Combined Experiment: All Optimizations ===");
        runBenchmark(env, "combinedAllOptimizations", (network, params) -> {
            // Apply all config optimizations
            network.withConfigValue("event.creation.maxOtherParents", params.numberOfNodes())
                    .withConfigValue("event.creation.antiSelfishnessFactor", 8)
                    .withConfigValue("event.creation.maxCreationRate", 0)
                    .withConfigValue("event.creation.period", "400us")
                    .withConfigValue("broadcast.enableBroadcast", true)
                    .withConfigValue("sync.pingPeriod", "100ms")
                    .withConfigValue("modules.eventIntake", "org.hiero.consensus.event.intake.impl")
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
