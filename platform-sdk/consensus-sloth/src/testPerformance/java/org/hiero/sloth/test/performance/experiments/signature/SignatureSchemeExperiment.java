// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.test.performance.experiments.signature;

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
 * Experiment testing the effect of signature scheme on consensus latency.
 */
@SuppressWarnings("NewClassNamingConvention")
@SlothSpecs(randomNodeIds = false)
@ContainerSpecs(proxyEnabled = false)
public class SignatureSchemeExperiment {

    private static final Logger log = LogManager.getLogger(SignatureSchemeExperiment.class);

    /**
     * Test ED25519 signature scheme.
     */
    @Benchmark
    void signatureSchemeED25519(@NonNull final TestEnvironment env) {
        log.info("=== SignatureScheme Experiment: ED25519 ===");
        runBenchmark(env, "signatureSchemeED25519", network -> {
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
