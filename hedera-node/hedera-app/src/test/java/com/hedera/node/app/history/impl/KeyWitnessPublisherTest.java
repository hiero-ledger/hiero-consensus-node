// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KeyWitnessPublisherTest {
    private static final long GENERATION = 7;
    private static final Bytes VK = Bytes.wrap("adopted verification key");
    private static final Instant ACTIVATION = Instant.ofEpochSecond(1_000);
    private static final Instant NOW = ACTIVATION.plusSeconds(10);
    private static final HintsKeyWitness WITNESS = witness(GENERATION, NOW.minusSeconds(1));
    private static final HintsKeyWitnessRecord PENDING = pending(GENERATION, VK);

    @Mock
    private ReadableHistoryStore store;

    private final List<HintsKeyWitness> submissions = new ArrayList<>();
    private CompletableFuture<Void> result;
    private KeyWitnessPublisher subject;

    @BeforeEach
    void setUp() {
        result = CompletableFuture.completedFuture(null);
        subject = new KeyWitnessPublisher(witness -> {
            submissions.add(witness);
            return result;
        });
    }

    @Test
    void submissionSuccessIsNotDurableAcceptanceAndRetriesStopOnlyWhenStateConfirms() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        subject.offer(VK, WITNESS);
        subject.offer(VK, witness(GENERATION, NOW));
        subject.reconcile(store, NOW.plusSeconds(29), true, GENERATION);
        assertEquals(List.of(WITNESS), submissions);

        subject.reconcile(store, NOW.plus(KeyWitnessPublisher.RETRY_INTERVAL), true, GENERATION);
        assertEquals(List.of(WITNESS, witness(GENERATION, NOW)), submissions);

        records(PENDING.copyBuilder().witness(WITNESS).build());
        subject.reconcile(store, NOW.plusSeconds(60), true, GENERATION);
        subject.offer(VK, WITNESS);
        subject.reconcile(store, NOW.plusSeconds(90), true, GENERATION);
        assertEquals(2, submissions.size());
    }

    @Test
    void delayedSignatureAndRetriesRetainTheirGenerationAfterAnotherAdoption() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        // A later generation can be adopted before this generation's signature completes.
        records(PENDING, pending(8, Bytes.wrap("new active key")));
        subject.reconcile(store, NOW.plusSeconds(1), true, 8);
        subject.offer(VK, WITNESS);
        subject.reconcile(store, NOW.plusSeconds(31), true, 8);

        assertEquals(List.of(WITNESS, WITNESS), submissions);
        assertEquals(GENERATION, submissions.getFirst().targetHintsConstructionId());
    }

    @Test
    void ignoresUnadoptedOrWrongKeyAndBlocksThatStraddleActivation() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        subject.offer(VK, witness(99, NOW));
        subject.offer(Bytes.wrap("different key"), WITNESS);
        subject.offer(VK, witness(GENERATION, ACTIVATION));
        subject.offer(VK, witness(GENERATION, ACTIVATION.minusSeconds(1)));
        subject.offer(
                VK,
                WITNESS.copyBuilder()
                        .timestamp((com.hedera.hapi.node.base.Timestamp) null)
                        .build());
        subject.offer(VK, WITNESS.copyBuilder().signature(Bytes.EMPTY).build());

        assertTrue(submissions.isEmpty());
        subject.offer(VK, WITNESS);
        assertEquals(List.of(WITNESS), submissions);
    }

    @Test
    void failedSubmissionUsesBoundedExponentialBackoffWithoutLosingCandidate() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        result = CompletableFuture.failedFuture(new IllegalStateException("transient submission failure"));
        subject.offer(VK, WITNESS);
        final long[] attemptTimes = {0, 30, 90, 210, 450, 750, 1050};
        for (int i = 1; i < attemptTimes.length; i++) {
            subject.reconcile(store, NOW.plusSeconds(attemptTimes[i] - 1), true, GENERATION);
            subject.offer(VK, WITNESS);
            assertEquals(i, submissions.size());
            subject.reconcile(store, NOW.plusSeconds(attemptTimes[i]), true, GENERATION);
            assertEquals(i + 1, submissions.size());
        }
        assertEquals(KeyWitnessPublisher.MAX_RETRY_INTERVAL.toSeconds(), attemptTimes[6] - attemptTimes[5]);
    }

    @Test
    void inactiveNodeRetainsCandidateAndPublishesWhenActiveAgain() {
        records(PENDING);
        subject.reconcile(store, NOW, false, GENERATION);
        subject.offer(VK, WITNESS);
        assertTrue(submissions.isEmpty());

        subject.reconcile(store, NOW.plusSeconds(1), true, GENERATION);
        assertEquals(List.of(WITNESS), submissions);
    }

    @Test
    void restartReconstructsPendingWorkAndUsesALaterBlock() {
        records(PENDING);
        subject.reconcile(store, NOW, false, GENERATION);
        subject.offer(VK, WITNESS);
        subject.stop();
        subject.offer(VK, WITNESS);
        subject.reconcile(store, NOW.plusSeconds(30), true, GENERATION);
        assertTrue(submissions.isEmpty());

        final var laterWitness = witness(GENERATION, NOW.plusSeconds(20));
        subject.offer(VK, laterWitness);
        assertEquals(List.of(laterWitness), submissions);
    }

    @Test
    void legacyAdoptionWithoutKnownBoundaryAllowsHistoricalBackfill() {
        records(PENDING.copyBuilder()
                .activationTime((com.hedera.hapi.node.base.Timestamp) null)
                .build());
        subject.reconcile(store, NOW.plusSeconds(365 * 86_400L), true, GENERATION);
        final var oldWitness = witness(GENERATION, ACTIVATION.minusSeconds(10));
        subject.offer(VK, oldWitness);

        assertEquals(List.of(oldWitness), submissions);
    }

    @Test
    void scansHistoryOnlyAtStartupAndGenerationChangesAndBoundsPendingRereads() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        subject.reconcile(store, NOW.plusSeconds(1), true, GENERATION);
        subject.reconcile(store, NOW.plusSeconds(2), true, GENERATION);
        verify(store, times(1)).keyProvenanceWithWitnesses();
        verify(store, never()).getKeyWitnessRecord(GENERATION);
        subject.reconcile(store, NOW.plus(KeyWitnessPublisher.STATE_REFRESH_INTERVAL), true, GENERATION);
        verify(store).getKeyWitnessRecord(GENERATION);
        subject.reconcile(store, NOW.plusSeconds(31), true, GENERATION + 1);
        verify(store, times(2)).keyProvenanceWithWitnesses();
    }

    @Test
    void removedAdoptionOrChangedBindingDiscardsLocalCandidate() {
        records(PENDING);
        subject.reconcile(store, NOW, false, GENERATION);
        subject.offer(VK, WITNESS);
        records(pending(GENERATION, Bytes.wrap("replaced source state")));
        subject.reconcile(store, NOW.plus(KeyWitnessPublisher.STATE_REFRESH_INTERVAL), true, GENERATION);
        assertTrue(submissions.isEmpty());

        records();
        subject.reconcile(store, NOW.plusSeconds(60), true, GENERATION);
        subject.offer(VK, WITNESS);
        assertTrue(submissions.isEmpty());
    }

    @Test
    void onlyOneSubmissionMayBeInFlightForAGeneration() {
        records(PENDING);
        result = new CompletableFuture<>();
        subject.reconcile(store, NOW, true, GENERATION);
        subject.offer(VK, WITNESS);
        subject.reconcile(store, NOW.plusSeconds(300), true, GENERATION);
        subject.offer(VK, witness(GENERATION, NOW.plusSeconds(200)));
        assertEquals(List.of(WITNESS), submissions);

        result.completeExceptionally(new IllegalStateException("eventual failure"));
        result = CompletableFuture.completedFuture(null);
        subject.reconcile(store, NOW.plusSeconds(301), true, GENERATION);
        assertEquals(List.of(WITNESS, witness(GENERATION, NOW.plusSeconds(200))), submissions);
    }

    @Test
    void throwingSubmitterIsIsolatedAndStillThrottled() {
        final var attempts = new AtomicInteger();
        subject = new KeyWitnessPublisher(witness -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("executor rejected submission");
        });
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        assertDoesNotThrow(() -> subject.offer(VK, WITNESS));
        subject.reconcile(store, NOW.plusSeconds(1), true, GENERATION);
        assertEquals(1, attempts.get());
        subject.reconcile(store, NOW.plus(KeyWitnessPublisher.RETRY_INTERVAL), true, GENERATION);
        assertEquals(2, attempts.get());
    }

    @Test
    void backwardsConsensusClockReloadsStateAndResetsRetrySchedule() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        subject.offer(VK, WITNESS);
        subject.reconcile(store, NOW.minusSeconds(1), true, GENERATION);

        assertEquals(List.of(WITNESS, WITNESS), submissions);
        verify(store, times(2)).keyProvenanceWithWitnesses();
        subject.reconcile(store, NOW, true, GENERATION);
        assertEquals(2, submissions.size());
    }

    @Test
    void concurrentOffersAndReconcilesDoNotDuplicateAnInflightSubmission() throws Exception {
        records(PENDING);
        result = new CompletableFuture<>();
        subject.reconcile(store, NOW, true, GENERATION);
        final var ready = new CountDownLatch(2);
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var offers = executor.submit(() -> {
                ready.countDown();
                await(start);
                for (int i = 0; i < 100; i++) {
                    subject.offer(VK, WITNESS);
                }
            });
            final var reconciles = executor.submit(() -> {
                ready.countDown();
                await(start);
                for (int i = 0; i < 100; i++) {
                    subject.reconcile(store, NOW.plusSeconds(i), true, GENERATION);
                }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            offers.get(5, TimeUnit.SECONDS);
            reconciles.get(5, TimeUnit.SECONDS);
        }
        assertEquals(List.of(WITNESS), submissions);
    }

    @Test
    void reportsPendingCountAndAgeWithoutInventingLegacyActivationTimes() {
        final var pendingCount = new AtomicInteger();
        final var oldestAge = new AtomicLong();
        subject = new KeyWitnessPublisher(witness -> CompletableFuture.completedFuture(null), (count, age) -> {
            pendingCount.set(count);
            oldestAge.set(age);
        });
        records(
                PENDING,
                pending(8, VK)
                        .copyBuilder()
                        .activationTime((com.hedera.hapi.node.base.Timestamp) null)
                        .build());
        subject.reconcile(store, NOW, true, GENERATION);
        assertEquals(2, pendingCount.get());
        assertEquals(10, oldestAge.get());
        subject.reconcile(store, NOW.plusSeconds(1), true, GENERATION);
        assertEquals(11, oldestAge.get());
        subject.stop();
        assertEquals(0, pendingCount.get());
        assertEquals(0, oldestAge.get());
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void records(final HintsKeyWitnessRecord... records) {
        lenient()
                .when(store.keyProvenanceWithWitnesses())
                .thenAnswer(ignored -> Arrays.asList(records).iterator());
        lenient().when(store.getKeyWitnessRecord(anyLong())).thenAnswer(invocation -> {
            final long id = invocation.getArgument(0);
            return Arrays.stream(records)
                    .filter(record -> record.provenanceOrThrow().targetHintsConstructionId() == id)
                    .findFirst()
                    .orElse(null);
        });
    }

    private static HintsKeyWitness witness(final long generation, final Instant timestamp) {
        return HintsKeyWitness.newBuilder()
                .targetHintsConstructionId(generation)
                .timestamp(asTimestamp(timestamp))
                .siblingHash(Bytes.wrap(new byte[32]))
                .signature(Bytes.wrap(new byte[SIGNATURE_LENGTH]))
                .build();
    }

    private static HintsKeyWitnessRecord pending(final long generation, final Bytes verificationKey) {
        return HintsKeyWitnessRecord.newBuilder()
                .provenance(HintsKeyProvenance.newBuilder()
                        .targetHintsConstructionId(generation)
                        .targetVerificationKey(verificationKey))
                .activationTime(asTimestamp(ACTIVATION))
                .build();
    }
}
