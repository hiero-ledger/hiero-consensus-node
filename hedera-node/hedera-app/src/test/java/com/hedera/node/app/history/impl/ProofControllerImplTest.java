// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProofControllerImplTest {

    private static final long SELF_ID = 1L;
    private static final long OTHER_NODE_ID = 2L;
    private static final long CONSTRUCTION_ID = 100L;
    private static final Bytes METADATA = Bytes.wrap("meta");
    private static final Bytes PROOF_KEY_1 = Bytes.wrap("pk1");
    private static final String RECOVERABLE_REASON =
            "Still missing messages from R1 nodes [2] after end of grace period for phase R2";
    private static final TssConfig DEFAULT_TSS_CONFIG = DEFAULT_CONFIG.getConfigData(TssConfig.class);

    private Executor executor;

    @Mock
    private HistoryService historyService;

    @Mock
    private HistorySubmissions submissions;

    @Mock
    private WrapsMpcStateMachine machine;

    @Mock
    private HistoryLibrary historyLibrary;

    @Mock
    private HistoryProver.Factory proverFactory;

    @Mock
    private HistoryProver prover;

    @Mock
    private WritableHistoryStore writableHistoryStore;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private RosterTransitionWeights weights;

    private final Map<Long, HistoryProofVote> existingVotes = new TreeMap<>();
    private final List<ProofKeyPublication> keyPublications = new ArrayList<>();
    private final List<WrapsMessagePublication> wrapsMessagePublications = new ArrayList<>();

    private ProofKeysAccessorImpl.SchnorrKeyPair keyPair;
    private HistoryProofConstruction construction;
    private ProofControllerImpl subject;

    @BeforeEach
    void setUp() {
        executor = Runnable::run;

        keyPair = new ProofKeysAccessorImpl.SchnorrKeyPair(Bytes.wrap("sk"), Bytes.wrap("pk"));

        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .gracePeriodEndTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
                .build();

        given(proverFactory.create(
                        eq(SELF_ID),
                        eq(DEFAULT_TSS_CONFIG),
                        eq(keyPair),
                        any(),
                        eq(weights),
                        any(),
                        any(),
                        eq(historyLibrary),
                        eq(submissions)))
                .willReturn(prover);

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);
    }

    @Test
    void constructionIdDelegatesToModel() {
        assertEquals(CONSTRUCTION_ID, subject.constructionId());
    }

    @Test
    void isStillInProgressTrueWhenNoProofOrFailure() {
        assertTrue(subject.isStillInProgress(DEFAULT_TSS_CONFIG));
    }

    @Test
    void isStillInProgressFalseWhenHasTargetProof() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetProof(HistoryProof.newBuilder().build())
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        assertFalse(subject.isStillInProgress(DEFAULT_TSS_CONFIG));
    }

    @Test
    void isStillInProgressFalseWhenHasFailureReason() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .failureReason("fail")
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        assertFalse(subject.isStillInProgress(DEFAULT_TSS_CONFIG));
    }

    @Test
    void advanceConstructionReturnsEarlyWhenAlreadyFinished() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetProof(HistoryProof.newBuilder().build())
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        subject.advanceConstruction(Instant.EPOCH, METADATA, writableHistoryStore, true, tssConfig);

        verifyNoMoreInteractions(writableHistoryStore, prover);
    }

    @Test
    void advanceConstructionPublishesKeyWhenMetadataMissingAndActive() {
        given(weights.targetIncludes(SELF_ID)).willReturn(true);

        final CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        given(submissions.submitProofKeyPublication(any())).willReturn(future);

        subject.advanceConstruction(Instant.EPOCH, null, writableHistoryStore, true, tssConfig);

        verify(submissions).submitProofKeyPublication(eq(keyPair.publicKey()));
    }

    @Test
    void advanceConstructionDoesNotPublishKeyWhenInactive() {
        subject.advanceConstruction(Instant.EPOCH, null, writableHistoryStore, false, tssConfig);

        verify(submissions, never()).submitProofKeyPublication(any());
    }

    @Test
    void advanceConstructionDoesNothingWhenAssemblyStartedAndInactive() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, false, tssConfig);

        verifyNoMoreInteractions(writableHistoryStore, prover);
    }

    @Test
    void advanceConstructionDelegatesToProverWhenAssemblyStartedAndInProgress() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).getLedgerId();
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any());
        verify(writableHistoryStore).getConstructionOrThrow(CONSTRUCTION_ID);
    }

    @Test
    void advanceConstructionFinishesProofWhenProverCompletes() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var proof = HistoryProof.newBuilder().build();

        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any()))
                .willReturn(new HistoryProver.Outcome.Completed(proof));
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).getLedgerId();
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any());
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
        verify(historyService).onFinished(eq(writableHistoryStore), eq(construction), any());
    }

    @Test
    void advanceConstructionFailsConstructionWhenProverFails() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var reason = "test-failure";

        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any()))
                .willReturn(new HistoryProver.Outcome.Failed(reason));
        given(writableHistoryStore.failForReason(CONSTRUCTION_ID, reason)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).getLedgerId();
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any());
        verify(writableHistoryStore).failForReason(CONSTRUCTION_ID, reason);
    }

    @Test
    void advanceConstructionRestartsOnRecoverableWrapsFailure() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var restarted = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();

        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any()))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON));
        given(weights.sourceNodeIds()).willReturn(Set.of(SELF_ID, OTHER_NODE_ID));
        given(writableHistoryStore.restartWrapsSigning(CONSTRUCTION_ID, Set.of(SELF_ID, OTHER_NODE_ID)))
                .willReturn(restarted);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore).restartWrapsSigning(CONSTRUCTION_ID, Set.of(SELF_ID, OTHER_NODE_ID));
        verify(writableHistoryStore, never()).failForReason(anyLong(), any());
    }

    @Test
    void advanceConstructionRecoversFailedConstructionAtStart() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .failureReason(RECOVERABLE_REASON)
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var restarted = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(construction);
        given(weights.sourceNodeIds()).willReturn(Set.of(SELF_ID, OTHER_NODE_ID));
        given(writableHistoryStore.restartWrapsSigning(CONSTRUCTION_ID, Set.of(SELF_ID, OTHER_NODE_ID)))
                .willReturn(restarted);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(1), null, writableHistoryStore, false, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore).getConstructionOrThrow(CONSTRUCTION_ID);
        verify(writableHistoryStore).restartWrapsSigning(CONSTRUCTION_ID, Set.of(SELF_ID, OTHER_NODE_ID));
        verify(writableHistoryStore, never()).failForReason(anyLong(), any());
    }

    @Test
    void addProofKeyPublicationIgnoredWhenNoGracePeriod() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var publication = new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH);

        subject.addProofKeyPublication(publication);

        // No exception and no interaction with weights (used by maybeUpdateForProofKey)
        verify(weights, never()).targetIncludes(anyLong());
    }

    @Test
    void addProofKeyPublicationTracksKeysForTargetNode() {
        given(weights.targetIncludes(SELF_ID)).willReturn(true);

        final var publication = new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH);

        subject.addProofKeyPublication(publication);

        // Exercise publishedWeight via advanceConstruction when after grace period and threshold reached
        given(weights.numTargetNodesInSource()).willReturn(1);

        given(writableHistoryStore.setAssemblyTime(eq(CONSTRUCTION_ID), any())).willReturn(construction);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(20), METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).setAssemblyTime(eq(CONSTRUCTION_ID), any());
    }

    @Test
    void addWrapsMessagePublicationReturnsFalseWhenHasTargetProof() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetProof(HistoryProof.newBuilder().build())
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var publication = new WrapsMessagePublication(SELF_ID, Bytes.EMPTY, R1, Instant.EPOCH);

        final var result = subject.addWrapsMessagePublication(publication, writableHistoryStore);

        assertFalse(result);
        verify(prover, never()).addWrapsSigningMessage(anyLong(), any(), any());
    }

    @Test
    void addWrapsMessagePublicationDelegatesToProverOtherwise() {
        final var publication = new WrapsMessagePublication(SELF_ID, Bytes.EMPTY, R1, Instant.EPOCH);

        given(prover.addWrapsSigningMessage(eq(CONSTRUCTION_ID), eq(publication), eq(writableHistoryStore)))
                .willReturn(true);

        final var result = subject.addWrapsMessagePublication(publication, writableHistoryStore);

        assertTrue(result);
        verify(prover).addWrapsSigningMessage(eq(CONSTRUCTION_ID), eq(publication), eq(writableHistoryStore));
    }

    @Test
    void addProofVoteIgnoresWhenAlreadyCompleted() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetProof(HistoryProof.newBuilder().build())
                .build();

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var vote = HistoryProofVote.newBuilder()
                .proof(HistoryProof.newBuilder().build())
                .build();

        subject.addProofVote(SELF_ID, vote, writableHistoryStore);

        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
    }

    @Test
    void addProofVoteStoresDirectProofVoteAndMayFinish() {
        final var proof = HistoryProof.newBuilder().build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);
        given(writableHistoryStore.completeProof(eq(CONSTRUCTION_ID), eq(proof)))
                .willReturn(construction);

        subject.addProofVote(SELF_ID, vote, writableHistoryStore);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(vote));
        verify(writableHistoryStore).completeProof(eq(CONSTRUCTION_ID), eq(proof));
        verify(historyService).onFinished(eq(writableHistoryStore), any(), any());
    }

    @Test
    void addProofVoteHandlesCongruentVotes() {
        final var proof = HistoryProof.newBuilder().build();
        final var baseVote = HistoryProofVote.newBuilder().proof(proof).build();
        existingVotes.put(OTHER_NODE_ID, baseVote);

        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                weights,
                executor,
                submissions,
                machine,
                keyPublications,
                wrapsMessagePublications,
                existingVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                DEFAULT_TSS_CONFIG);

        final var congruentVote =
                HistoryProofVote.newBuilder().congruentNodeId(OTHER_NODE_ID).build();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightOf(OTHER_NODE_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(15L);
        given(writableHistoryStore.completeProof(eq(CONSTRUCTION_ID), any())).willReturn(construction);

        subject.addProofVote(SELF_ID, congruentVote, writableHistoryStore);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(congruentVote));
    }

    @Test
    void cancelPendingWorkCancelsPublicationAndProver() throws Exception {
        final var future = new CompletableFuture<Void>();
        setField("publicationFuture", future);

        given(prover.cancelPendingWork()).willReturn(true);

        subject.cancelPendingWork();

        assertTrue(future.isCancelled());
        verify(prover).cancelPendingWork();
    }

    @Test
    void cancelPendingWorkForwardsToProver() {
        subject.cancelPendingWork();

        verify(prover).cancelPendingWork();
    }

    private static Timestamp asTimestamp(final Instant instant) {
        return new Timestamp(instant.getEpochSecond(), instant.getNano());
    }

    private void setField(String name, Object value) throws Exception {
        final var field = ProofControllerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(subject, value);
    }
}
