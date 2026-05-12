// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Reproduces the race condition behind the {@code BlockRecordManagerTest#[4] NON_GENESIS, true} flake
 * (issue #25312): a single {@link org.hiero.base.crypto.internal.JcaSigner} shared across threads
 * holds one {@link Signature} object whose {@code update}/{@code sign} sequence is not atomic. When
 * the concurrent record stream producer closes two blocks on the {@code ForkJoinPool} at the same
 * time, both closures call {@code signer.sign(...)} on the same {@code JcaSigner} and the bytes
 * accumulated in the shared {@code Signature} interleave, producing an unverifiable signature.
 *
 * <p>These tests hammer a shared signer from many threads using unique data per call and assert
 * that every signature verifies. Without synchronization on the shared {@code Signature}, the
 * stress test fails within a handful of iterations; with the fix it completes without a single bad
 * signature.
 */
class JcaSignerConcurrencyTest {

    private static final int THREAD_COUNT = 16;
    private static final int SIGNS_PER_THREAD = 200;

    @ParameterizedTest
    @EnumSource(
            value = SigningImplementation.class,
            names = {"RSA_BC", "RSA_JDK", "ED25519_SUN"})
    void jcaSignerIsSafeUnderConcurrentSigning(final SigningImplementation implementation) throws Exception {
        final SecureRandom secureRandom = new SecureRandom();
        final KeyPair keyPair = SigningFactory.generateKeyPair(implementation.getSigningSchema(), secureRandom);
        // One signer shared across all threads — the production setup that the race needs.
        final BytesSigner sharedSigner = SigningFactory.createSigner(implementation, keyPair);

        runStress(implementation, keyPair, sharedSigner);
    }

    /**
     * Sanity check: when each thread owns its own signer, no race is possible and the same workload
     * passes trivially. This guards against the stress test masking a different latent failure.
     */
    @Test
    void perThreadSignerHasNoRace() throws Exception {
        final SigningImplementation implementation = SigningImplementation.RSA_BC;
        final SecureRandom secureRandom = new SecureRandom();
        final KeyPair keyPair = SigningFactory.generateKeyPair(implementation.getSigningSchema(), secureRandom);

        runStress(implementation, keyPair, null /* perThreadSigner */);
    }

    /**
     * Drive {@link THREAD_COUNT} threads to sign {@link SIGNS_PER_THREAD} unique payloads each.
     * If {@code sharedSigner} is {@code null}, each thread builds its own signer (no sharing). Every
     * resulting signature is verified against the exact payload that produced it; any mismatch is
     * reported as a failure.
     */
    private static void runStress(
            final SigningImplementation implementation, final KeyPair keyPair, final BytesSigner sharedSigner)
            throws Exception {
        // readyGate: workers signal "I'm at the start line"; main waits for all of them.
        // startGate: main releases all workers at once to maximize update()/sign() overlap.
        final CountDownLatch readyGate = new CountDownLatch(THREAD_COUNT);
        final CountDownLatch startGate = new CountDownLatch(1);
        final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        // try-with-resources guarantees pool.close() on exit; explicit shutdownNow() on failure paths
        // keeps that close() from blocking on workers still parked at the start gate.
        try (final ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    final BytesSigner signer =
                            sharedSigner != null ? sharedSigner : SigningFactory.createSigner(implementation, keyPair);
                    try {
                        // Each thread brings its own verifier — the verifier code path is also stateful
                        // and we don't want to conflate it with the signer race we're hunting.
                        final Signature verifier = Signature.getInstance(
                                implementation.getSigningSchema().getSigningAlgorithm(), implementation.getProvider());
                        verifier.initVerify(keyPair.getPublic());
                        final MessageDigest digest = MessageDigest.getInstance("SHA-384");

                        readyGate.countDown();
                        startGate.await();

                        for (int i = 0; i < SIGNS_PER_THREAD; i++) {
                            // Unique 48-byte payload per (thread, iteration), mirroring the SHA-384 hash
                            // the record stream signs.
                            final byte[] raw = (threadId + ":" + i).getBytes();
                            digest.reset();
                            final Bytes payload = Bytes.wrap(digest.digest(raw));

                            final Bytes signature = signer.sign(payload);

                            payload.updateSignature(verifier);
                            final boolean ok = signature.verifySignature(verifier);
                            if (!ok) {
                                failures.add("thread=" + threadId + " iter=" + i + " payloadHex=" + payload.toHex());
                            }
                        }
                    } catch (final Exception e) {
                        failures.add("thread=" + threadId + " exception=" + e);
                    }
                });
            }

            if (!readyGate.await(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("Worker threads did not reach the start gate in time for " + implementation);
            }
            startGate.countDown();

            pool.shutdown();
            if (!pool.awaitTermination(2, TimeUnit.MINUTES)) {
                pool.shutdownNow();
                fail("Stress workload did not finish in time for " + implementation);
            }
        }

        final List<String> failureList = List.copyOf(failures);
        assertEquals(
                List.of(),
                failureList,
                "Concurrent signing produced unverifiable signatures with " + implementation
                        + ". First few failures: "
                        + failureList.subList(0, Math.min(5, failureList.size())));
    }
}
