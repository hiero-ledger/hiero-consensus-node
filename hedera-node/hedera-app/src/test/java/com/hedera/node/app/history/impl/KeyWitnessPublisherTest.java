// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
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
    void failedSubmissionRetriesAtTheNextOpportunityWithoutLosingCandidate() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        result = CompletableFuture.failedFuture(new IllegalStateException("transient submission failure"));
        subject.offer(VK, WITNESS);
        result = CompletableFuture.completedFuture(null);
        subject.reconcile(store, NOW.plusSeconds(1), true, GENERATION);

        assertEquals(List.of(WITNESS, WITNESS), submissions);
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
    void scansHistoricalQueueOnlyAtStartupAndGenerationChanges() {
        records(PENDING);
        subject.reconcile(store, NOW, true, GENERATION);
        subject.reconcile(store, NOW.plusSeconds(1), true, GENERATION);
        subject.reconcile(store, NOW.plusSeconds(2), true, GENERATION);
        verify(store, times(1)).keyProvenanceWithWitnesses();
        verify(store, times(2)).getKeyWitnessRecord(GENERATION);
        subject.reconcile(store, NOW.plusSeconds(3), true, GENERATION + 1);
        verify(store, times(2)).keyProvenanceWithWitnesses();
    }

    @Test
    void removedAdoptionOrChangedBindingDiscardsLocalCandidate() {
        records(PENDING);
        subject.reconcile(store, NOW, false, GENERATION);
        subject.offer(VK, WITNESS);
        records(pending(GENERATION, Bytes.wrap("replaced source state")));
        subject.reconcile(store, NOW.plusSeconds(1), true, GENERATION);
        assertTrue(submissions.isEmpty());

        records();
        subject.reconcile(store, NOW.plusSeconds(31), true, GENERATION);
        subject.offer(VK, WITNESS);
        assertTrue(submissions.isEmpty());
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
