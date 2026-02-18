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
@ContainerSpecs(proxyEnabled = false)
public class CombinedOptimizationsExperiment {

    private static final Logger log = LogManager.getLogger(CombinedOptimizationsExperiment.class);
    public static final BenchmarkParameters DEFAULTS = BenchmarkParameters.defaults();

    /**
     * Apply all identified optimizations together.
     */
    @OtterTest
    void combinedAllOptimizations(@NonNull final TestEnvironment env) {
        log.info("=== Combined Experiment: All Optimizations ===");
        runBenchmark(env, "combinedAllOptimizations", DEFAULTS, network -> {
            // Apply all config optimizations
            network.withConfigValue("event.creation.maxOtherParents", DEFAULTS.numberOfNodes());
            network.withConfigValue("event.creation.antiSelfishnessFactor", 8);
            network.withConfigValue("event.creation.maxCreationRate", 100);

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
