/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final TssKeyPair PROOF_KEY_PAIR = new TssKeyPair(Bytes.EMPTY, Bytes.EMPTY);
    private static final ProofKeyPublication SELF_KEY_PUBLICATION =
            new ProofKeyPublication(SELF_ID, Bytes.EMPTY, CONSENSUS_NOW);
    private static final HistorySignaturePublication SIGNATURE_PUBLICATION =
            new HistorySignaturePublication(1L, HistorySignature.DEFAULT, CONSENSUS_NOW);
    private static final HistoryProofConstruction UNFINISHED_CONSTRUCTION = HistoryProofConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .gracePeriodEndTime(asTimestamp(CONSENSUS_NOW.plusSeconds(1)))
            .build();
    private static final HistoryProofConstruction SCHEDULED_ASSEMBLY_CONSTRUCTION =
            HistoryProofConstruction.newBuilder()
                    .constructionId(CONSTRUCTION_ID)
                    .assemblyStartTime(asTimestamp(CONSENSUS_NOW))
                    .build();
    private static final HistoryProofConstruction FINISHED_CONSTRUCTION = HistoryProofConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .targetProof(HistoryProof.DEFAULT)
            .build();

    @Mock
    private HistoryLibrary library;

    @Mock
    private HistoryLibraryCodec codec;

    @Mock
    private HistorySubmissions submissions;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private Consumer<HistoryProof> proofConsumer;

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
    void finishedIsNotInProgressAndDoesNothing() {
        setupWith(FINISHED_CONSTRUCTION);
        scheduledTasks.poll();

        assertFalse(subject.isStillInProgress());

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store);

        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void ensuresProofKeyPublishedWhileWaitingForMetadata() {
        setupWith(UNFINISHED_CONSTRUCTION);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);

        subject.advanceConstruction(CONSENSUS_NOW, null, store);

        final var task = scheduledTasks.poll();
        assertNotNull(task);
        task.run();

        verify(submissions).submitProofKeyPublication(PROOF_KEY_PAIR.publicKey());

        // Does not re-publish key
        subject.advanceConstruction(CONSENSUS_NOW, null, store);
        assertTrue(scheduledTasks.isEmpty());

        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void doesNotPublishProofKeyIfAlreadyInState() {
        setupWith(UNFINISHED_CONSTRUCTION, List.of(SELF_KEY_PUBLICATION), List.of(), Map.of());

        subject.advanceConstruction(CONSENSUS_NOW, null, store);

        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void setsAssemblyStartTimeAndSchedulesSigningWhenAllNodesHavePublishedKeys() {
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        setupWith(UNFINISHED_CONSTRUCTION, List.of(SELF_KEY_PUBLICATION), List.of(), Map.of());
        given(weights.numTargetNodesInSource()).willReturn(1);
        given(store.setAssemblyTime(UNFINISHED_CONSTRUCTION.constructionId(), CONSENSUS_NOW))
                .willReturn(SCHEDULED_ASSEMBLY_CONSTRUCTION);
        given(codec.encodeAddressBook(Map.of(), Map.of(SELF_ID, PROOF_KEY_PAIR.publicKey())))
                .willReturn(Bytes.EMPTY);
        given(library.hashAddressBook(Bytes.EMPTY)).willReturn(Bytes.EMPTY);
        final var mockHistory = new History(Bytes.EMPTY, METADATA);
        given(codec.encodeHistory(mockHistory)).willReturn(Bytes.EMPTY);
        given(library.signSchnorr(Bytes.EMPTY, PROOF_KEY_PAIR.privateKey())).willReturn(Bytes.EMPTY);
        final var expectedSignature = new HistorySignature(mockHistory, Bytes.EMPTY);
        given(submissions.submitAssemblySignature(CONSTRUCTION_ID, expectedSignature))
                .willReturn(CompletableFuture.completedFuture(null));

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store);

        runScheduledTasks();

        verify(submissions).submitAssemblySignature(CONSTRUCTION_ID, expectedSignature);
        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void startsSigningFutureOnceAssemblyScheduledButInsufficientSignaturesKnown() {
        setupWith(SCHEDULED_ASSEMBLY_CONSTRUCTION, List.of(), List.of(), Map.of());

        given(codec.encodeAddressBook(Map.of(), Map.of())).willReturn(Bytes.EMPTY);
        given(library.hashAddressBook(Bytes.EMPTY)).willReturn(Bytes.EMPTY);
        final var mockHistory = new History(Bytes.EMPTY, METADATA);
        given(codec.encodeHistory(mockHistory)).willReturn(Bytes.EMPTY);
        given(library.signSchnorr(Bytes.EMPTY, PROOF_KEY_PAIR.privateKey())).willReturn(Bytes.EMPTY);
        final var expectedSignature = new HistorySignature(mockHistory, Bytes.EMPTY);
        given(submissions.submitAssemblySignature(CONSTRUCTION_ID, expectedSignature))
                .willReturn(CompletableFuture.completedFuture(null));

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store);

        runScheduledTasks();
        verify(submissions).submitAssemblySignature(CONSTRUCTION_ID, expectedSignature);

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store);
        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void ensuresProofKeyPublishedWhileGracePeriodStillInEffect() {
        setupWith(UNFINISHED_CONSTRUCTION);
        given(weights.numTargetNodesInSource()).willReturn(1);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store);

        final var task = scheduledTasks.poll();
        assertNotNull(task);
        task.run();

        verify(submissions).submitProofKeyPublication(PROOF_KEY_PAIR.publicKey());
    }

    @Test
    void noOpIfAssemblyWasFixedAndVoteAlreadyCast() {
        setupWith(SCHEDULED_ASSEMBLY_CONSTRUCTION, List.of(), List.of(), Map.of(SELF_ID, HistoryProofVote.DEFAULT));

        subject.advanceConstruction(CONSENSUS_NOW, METADATA, store);

        assertTrue(scheduledTasks.isEmpty());
    }

    private void setupWith(@NonNull final HistoryProofConstruction construction) {
        setupWith(construction, List.of(), List.of(), Map.of());
    }

    private void setupWith(
            @NonNull final HistoryProofConstruction construction,
            @NonNull final List<ProofKeyPublication> proofKeyPublications,
            @NonNull final List<HistorySignaturePublication> signaturePublications,
            @NonNull final Map<Long, HistoryProofVote> votes) {
        subject = new ProofControllerImpl(
                SELF_ID,
                PROOF_KEY_PAIR,
                LEDGER_ID,
                construction,
                weights,
                scheduledTasks::offer,
                library,
                codec,
                submissions,
                proofKeyPublications,
                signaturePublications,
                votes,
                proofConsumer);
    }

    private void runScheduledTasks() {
        Runnable task;
        while ((task = scheduledTasks.poll()) != null) {
            task.run();
        }
    }
}
