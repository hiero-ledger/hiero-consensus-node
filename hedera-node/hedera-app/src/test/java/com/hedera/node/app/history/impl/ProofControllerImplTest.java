// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProofControllerImplTest {
    private static final long SELF_ID = 42L;
    private static final long CONSTRUCTION_ID = 123L;
    private static final Bytes METADATA = Bytes.wrap("M");
    private static final Bytes LEDGER_ID = Bytes.wrap("LID");
    private static final ChainOfTrustProof PROOF =
            ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("P")).build();
    private static final Bytes SIGNATURE = Bytes.wrap("S");
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final SchnorrKeyPair PROOF_KEY_PAIR = new SchnorrKeyPair(Bytes.EMPTY, Bytes.EMPTY);
    private static final ProofKeyPublication SELF_KEY_PUBLICATION =
            new ProofKeyPublication(SELF_ID, Bytes.EMPTY, CONSENSUS_NOW);
    private static final HistorySignaturePublication SELF_SIGNATURE_PUBLICATION =
            new HistorySignaturePublication(SELF_ID, new HistorySignature(History.DEFAULT, SIGNATURE), CONSENSUS_NOW);
    private static final HistoryProofConstruction UNFINISHED_CONSTRUCTION = HistoryProofConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .gracePeriodEndTime(asTimestamp(CONSENSUS_NOW.plusSeconds(1)))
            .build();
    private static final HistoryProofConstruction FAILED_CONSTRUCTION = HistoryProofConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .failureReason("Didn't work out")
            .build();
    private static final HistoryProofConstruction SCHEDULED_ASSEMBLY_CONSTRUCTION =
            HistoryProofConstruction.newBuilder()
                    .constructionId(CONSTRUCTION_ID)
                    .assemblyStartTime(asTimestamp(CONSENSUS_NOW))
                    .build();
    private static final HistoryProofConstruction SCHEDULED_ASSEMBLY_CONSTRUCTION_WITH_SOURCE_PROOF =
            HistoryProofConstruction.newBuilder()
                    .constructionId(CONSTRUCTION_ID)
                    .assemblyStartTime(asTimestamp(CONSENSUS_NOW))
                    .sourceProof(new HistoryProof(List.of(new ProofKey(SELF_ID, Bytes.EMPTY)), History.DEFAULT, PROOF))
                    .build();
    private static final HistoryProofConstruction FINISHED_CONSTRUCTION = HistoryProofConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .targetProof(HistoryProof.DEFAULT)
            .build();

    @Mock
    private HistoryLibrary library;

    @Mock
    private HistorySubmissions submissions;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private HistoryService historyService;

    @Mock
    private WritableHistoryStore store;

    private final Deque<Runnable> scheduledTasks = new ArrayDeque<>();

    private ProofControllerImpl subject;

    @Test
    void returnsConstructionIdForUnfinished() {
        setupWith(UNFINISHED_CONSTRUCTION);

        assertEquals(UNFINISHED_CONSTRUCTION.constructionId(), subject.constructionId());
        assertTrue(subject.isStillInProgress());
    }

    @Test
    void noOpWithFailedConstruction() {
        setupWith(FAILED_CONSTRUCTION);

        assertDoesNotThrow(() -> subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true));

        assertFalse(subject.isStillInProgress());
    }

    @Test
    void finishedIsNotInProgressAndDoesNothing() {
        setupWith(FINISHED_CONSTRUCTION);
        scheduledTasks.poll();

        assertFalse(subject.isStillInProgress());

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void ensuresProofKeyPublishedWhileWaitingForMetadata() {
        setupWith(UNFINISHED_CONSTRUCTION);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);

        subject.advanceConstruction(CONSENSUS_NOW, null, store, true);

        final var task = scheduledTasks.poll();
        assertNotNull(task);
        task.run();

        verify(submissions).submitProofKeyPublication(PROOF_KEY_PAIR.publicKey());

        // Does not re-publish key
        subject.advanceConstruction(CONSENSUS_NOW, null, store, true);
        assertTrue(scheduledTasks.isEmpty());

        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void doesNotPublishProofKeyIfAlreadyInState() {
        setupWith(UNFINISHED_CONSTRUCTION, List.of(SELF_KEY_PUBLICATION), List.of(), Map.of(), LEDGER_ID);

        subject.advanceConstruction(CONSENSUS_NOW, null, store, true);

        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void setsAssemblyStartTimeAndSchedulesSigningWhenAllNodesHavePublishedKeys() {
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        setupWith(UNFINISHED_CONSTRUCTION, List.of(SELF_KEY_PUBLICATION), List.of(), Map.of(), LEDGER_ID);
        given(weights.numTargetNodesInSource()).willReturn(1);
        given(store.setAssemblyTime(UNFINISHED_CONSTRUCTION.constructionId(), CONSENSUS_NOW))
                .willReturn(SCHEDULED_ASSEMBLY_CONSTRUCTION);
        given(library.hashAddressBook(any(), any())).willReturn(Bytes.EMPTY);
        final var mockHistory = new History(Bytes.EMPTY, METADATA);
        given(library.signSchnorr(any(), any())).willReturn(Bytes.EMPTY);
        given(library.hashHintsVerificationKey(any())).willReturn(Bytes.EMPTY);
        final var expectedSignature = new HistorySignature(mockHistory, Bytes.EMPTY);
        given(submissions.submitAssemblySignature(CONSTRUCTION_ID, expectedSignature))
                .willReturn(CompletableFuture.completedFuture(null));

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        runScheduledTasks();

        verify(submissions).submitAssemblySignature(CONSTRUCTION_ID, expectedSignature);
        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void startsSigningFutureOnceAssemblyScheduledButInsufficientSignaturesKnown() {
        given(library.hashHintsVerificationKey(any())).willReturn(Bytes.EMPTY);
        setupWith(SCHEDULED_ASSEMBLY_CONSTRUCTION, List.of(), List.of(), Map.of(), LEDGER_ID);

        given(library.hashAddressBook(any(), any())).willReturn(Bytes.EMPTY);
        final var mockHistory = new History(Bytes.EMPTY, METADATA);
        given(library.signSchnorr(any(), any())).willReturn(Bytes.EMPTY);
        final var expectedSignature = new HistorySignature(mockHistory, Bytes.EMPTY);
        given(submissions.submitAssemblySignature(CONSTRUCTION_ID, expectedSignature))
                .willReturn(CompletableFuture.completedFuture(null));

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        runScheduledTasks();
        verify(submissions).submitAssemblySignature(CONSTRUCTION_ID, expectedSignature);

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);
        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void ensuresProofKeyPublishedWhileGracePeriodStillInEffect() {
        setupWith(UNFINISHED_CONSTRUCTION);
        given(weights.numTargetNodesInSource()).willReturn(1);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        final var task = scheduledTasks.poll();
        assertNotNull(task);
        task.run();

        verify(submissions).submitProofKeyPublication(PROOF_KEY_PAIR.publicKey());
    }

    @Test
    void noOpIfAssemblyWasFixedAndVoteAlreadyCast() {
        setupWith(
                SCHEDULED_ASSEMBLY_CONSTRUCTION,
                List.of(),
                List.of(),
                Map.of(SELF_ID, HistoryProofVote.DEFAULT),
                LEDGER_ID);

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void startsAndUsesSourceProofWithSufficientSignatures() {
        given(library.hashHintsVerificationKey(any())).willReturn(Bytes.EMPTY);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(library.verifySchnorr(any(), any(), any())).willReturn(true);
        setupWith(
                SCHEDULED_ASSEMBLY_CONSTRUCTION_WITH_SOURCE_PROOF,
                List.of(SELF_KEY_PUBLICATION),
                List.of(SELF_SIGNATURE_PUBLICATION),
                Map.of(),
                LEDGER_ID);

        runScheduledTasks();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(3L);
        given(weights.sourceWeightThreshold()).willReturn(3L);

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        given(library.hashAddressBook(any(), any())).willReturn(Bytes.EMPTY);
        given(library.proveChainOfTrust(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(PROOF.wrapsProof());
        given(submissions.submitProofVote(eq(CONSTRUCTION_ID), argThat(v -> v.chainOfTrustProofOrThrow()
                        .equals(PROOF))))
                .willReturn(CompletableFuture.completedFuture(null));

        runScheduledTasks();

        verify(submissions).submitProofVote(eq(CONSTRUCTION_ID), argThat(v -> v.chainOfTrustProofOrThrow()
                .equals(PROOF)));
        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void withNullLedgerIdAndSufficientSignaturesUsesListProof() {
        given(library.hashHintsVerificationKey(any())).willReturn(Bytes.EMPTY);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(library.verifySchnorr(any(), any(), any())).willReturn(true);
        setupWith(
                SCHEDULED_ASSEMBLY_CONSTRUCTION,
                List.of(SELF_KEY_PUBLICATION),
                List.of(SELF_SIGNATURE_PUBLICATION),
                Map.of(),
                null);

        runScheduledTasks();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(3L);
        given(weights.sourceWeightThreshold()).willReturn(3L);
        given(library.hashAddressBook(any(), any())).willReturn(Bytes.EMPTY);
        given(store.completeProof(eq(CONSTRUCTION_ID), argThat(HistoryProof::hasChainOfTrustProof)))
                .willReturn(FINISHED_CONSTRUCTION);

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        assertFalse(subject.isStillInProgress());
    }

    @Test
    void votingIsNoopWithFinishedProof() {
        setupWith(FINISHED_CONSTRUCTION);

        subject.addProofVote(SELF_ID, HistoryProofVote.DEFAULT, store);

        verifyNoInteractions(store);
    }

    @Test
    void votingWorksAsExpectedWithKnownLedgerId() {
        setupWith(SCHEDULED_ASSEMBLY_CONSTRUCTION_WITH_SOURCE_PROOF);

        final var expectedProof =
                HistoryProof.newBuilder().chainOfTrustProof(PROOF).build();
        final var selfVote = HistoryProofVote.newBuilder().proof(expectedProof).build();
        given(weights.sourceWeightOf(SELF_ID)).willReturn(3L);
        given(weights.sourceWeightThreshold()).willReturn(4L);

        subject.addProofVote(SELF_ID, selfVote, store);

        verify(store).addProofVote(SELF_ID, CONSTRUCTION_ID, selfVote);
        verify(store, never()).completeProof(anyLong(), any());

        subject.addProofVote(SELF_ID, selfVote, store);

        verifyNoMoreInteractions(store);

        final long otherNodeId = 99L;
        final var otherProof = HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("OOPS")))
                .build();
        final var otherVote = HistoryProofVote.newBuilder().proof(otherProof).build();
        given(weights.sourceWeightOf(otherNodeId)).willReturn(3L);
        subject.addProofVote(otherNodeId, otherVote, store);

        verify(store, never()).completeProof(anyLong(), any());

        final long congruentNodeId = 0L;
        given(weights.sourceWeightOf(congruentNodeId)).willReturn(2L);
        final var congruentVote =
                HistoryProofVote.newBuilder().congruentNodeId(SELF_ID).build();
        given(store.completeProof(CONSTRUCTION_ID, expectedProof)).willReturn(FINISHED_CONSTRUCTION);

        subject.addProofVote(congruentNodeId, congruentVote, store);
    }

    @Test
    void votingWorksAsExpectedWithUnknownLedgerId() {
        setupWith(SCHEDULED_ASSEMBLY_CONSTRUCTION_WITH_SOURCE_PROOF, List.of(), List.of(), Map.of(), null);
        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store, true);

        final var expectedProof =
                HistoryProof.newBuilder().chainOfTrustProof(PROOF).build();
        final var selfVote = HistoryProofVote.newBuilder().proof(expectedProof).build();
        given(weights.sourceWeightOf(SELF_ID)).willReturn(3L);
        given(weights.sourceWeightThreshold()).willReturn(4L);

        subject.addProofVote(SELF_ID, selfVote, store);

        verify(store).addProofVote(SELF_ID, CONSTRUCTION_ID, selfVote);
        verify(store, never()).completeProof(anyLong(), any());

        final long otherNodeId = 99L;
        final var otherVote = HistoryProofVote.newBuilder().proof(expectedProof).build();
        given(weights.sourceWeightOf(otherNodeId)).willReturn(3L);
        given(store.completeProof(CONSTRUCTION_ID, expectedProof)).willReturn(FINISHED_CONSTRUCTION);

        subject.addProofVote(otherNodeId, otherVote, store);

        verify(historyService).onFinished(store, FINISHED_CONSTRUCTION);
    }

    private void setupWith(@NonNull final HistoryProofConstruction construction) {
        setupWith(construction, List.of(), List.of(), Map.of(), LEDGER_ID);
    }

    private void setupWith(
            @NonNull final HistoryProofConstruction construction,
            @NonNull final List<ProofKeyPublication> proofKeyPublications,
            @NonNull final List<HistorySignaturePublication> signaturePublications,
            @NonNull final Map<Long, HistoryProofVote> votes,
            @Nullable final Bytes ledgerId) {
        subject = new ProofControllerImpl(
                SELF_ID,
                true,
                PROOF_KEY_PAIR,
                ledgerId,
                construction,
                weights,
                scheduledTasks::offer,
                library,
                submissions,
                proofKeyPublications,
                signaturePublications,
                votes,
                historyService);
    }

    private void runScheduledTasks() {
        Runnable task;
        while ((task = scheduledTasks.poll()) != null) {
            task.run();
        }
    }
}
