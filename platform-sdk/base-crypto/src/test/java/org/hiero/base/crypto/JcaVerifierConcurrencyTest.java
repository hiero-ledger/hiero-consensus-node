// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
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
 * Companion to {@link JcaSignerConcurrencyTest}: proves that
 * {@link org.hiero.base.crypto.internal.JcaVerifier} is also safe to share across threads.
 *
 * <p>The verifier holds a single {@link java.security.Signature} object whose
 * {@code update}/{@code verify} pair is non-atomic, structurally identical to the bug in the signer
 * that produced issue #25312. Production callers (event intake, RSA hints context) defensively wrap
 * verifiers in {@code ThreadLocal} maps to avoid this race; this test pins down the contract so
 * callers no longer need that workaround.
 *
 * <p>Each thread signs unique payloads with its own private signer (a fresh {@code JcaSigner} per
 * thread, so the signing side is uncontested) and then verifies them against a single shared
 * {@code JcaVerifier}. Any false negative — or thrown exception from corrupted internal state — is
 * recorded as a failure.
 */
class JcaVerifierConcurrencyTest {

    private static final int THREAD_COUNT = 16;
    private static final int VERIFICATIONS_PER_THREAD = 200;

    @ParameterizedTest
    @EnumSource(
            value = SigningImplementation.class,
            names = {"RSA_BC", "RSA_JDK", "ED25519_SUN"})
    void jcaVerifierIsSafeUnderConcurrentVerification(final SigningImplementation implementation) throws Exception {
        final SecureRandom secureRandom = new SecureRandom();
        final KeyPair keyPair = SigningFactory.generateKeyPair(implementation.getSigningSchema(), secureRandom);
        // One verifier shared across all threads — the setup the bug needs.
        final BytesSignatureVerifier sharedVerifier =
                SigningFactory.createVerifier(implementation, keyPair.getPublic());

        runStress(implementation, keyPair, sharedVerifier);
    }

    /**
     * Sanity check: per-thread verifier (current production pattern) — must always pass and guards
     * against the stress test masking unrelated failures.
     */
    @Test
    void perThreadVerifierHasNoRace() throws Exception {
        final SigningImplementation implementation = SigningImplementation.RSA_BC;
        final SecureRandom secureRandom = new SecureRandom();
        final KeyPair keyPair = SigningFactory.generateKeyPair(implementation.getSigningSchema(), secureRandom);

        runStress(implementation, keyPair, null /* perThreadVerifier */);
    }

    /**
     * Pre-signs all payloads up front (single-threaded), then has {@link #THREAD_COUNT} threads
     * verify them concurrently against either a shared or per-thread verifier. Each thread checks
     * that every verify() returns {@code true}; any {@code false} or thrown exception is recorded.
     */
    private static void runStress(
            final SigningImplementation implementation,
            final KeyPair keyPair,
            final BytesSignatureVerifier sharedVerifier)
            throws Exception {

        // Pre-sign every payload single-threaded so the verify side is the only thing under test.
        final BytesSigner signer = SigningFactory.createSigner(implementation, keyPair);
        final List<List<SignedPayload>> perThreadWork = new ArrayList<>(THREAD_COUNT);
        final MessageDigest digest = MessageDigest.getInstance("SHA-384");
        for (int t = 0; t < THREAD_COUNT; t++) {
            final List<SignedPayload> work = new ArrayList<>(VERIFICATIONS_PER_THREAD);
            for (int i = 0; i < VERIFICATIONS_PER_THREAD; i++) {
                digest.reset();
                final Bytes payload = Bytes.wrap(digest.digest((t + ":" + i).getBytes()));
                final Bytes signature = signer.sign(payload);
                work.add(new SignedPayload(payload, signature));
            }
            perThreadWork.add(work);
        }

        // readyGate: workers signal "I'm at the start line"; main waits for all of them.
        // startGate: main releases all workers at once to maximize update()/verify() overlap.
        final CountDownLatch readyGate = new CountDownLatch(THREAD_COUNT);
        final CountDownLatch startGate = new CountDownLatch(1);
        final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        // try-with-resources guarantees pool.close() on exit; explicit shutdownNow() on failure paths
        // keeps that close() from blocking on workers still parked at the start gate.
        try (final ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    final BytesSignatureVerifier verifier = sharedVerifier != null
                            ? sharedVerifier
                            : SigningFactory.createVerifier(implementation, keyPair.getPublic());
                    final List<SignedPayload> work = perThreadWork.get(threadId);
                    try {
                        readyGate.countDown();
                        startGate.await();

                        for (int i = 0; i < work.size(); i++) {
                            final SignedPayload sp = work.get(i);
                            final boolean ok = verifier.verify(sp.payload(), sp.signature());
                            if (!ok) {
                                failures.add("thread=" + threadId + " iter=" + i + " payloadHex="
                                        + sp.payload().toHex());
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
                "Concurrent verification produced false negatives or exceptions with " + implementation
                        + ". First few failures: "
                        + failureList.subList(0, Math.min(5, failureList.size())));
    }

    private record SignedPayload(Bytes payload, Bytes signature) {}
}
