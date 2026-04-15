// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.AggregatedNodeSignatures;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
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
    private HistoryProofMetrics historyProofMetrics;

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
                historyProofMetrics,
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
                .targetProof(aValidProof())
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
                historyProofMetrics,
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        assertFalse(subject.isStillInProgress(DEFAULT_TSS_CONFIG));
    }

    @Test
    void advanceConstructionReturnsEarlyWhenAlreadyFinished() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetProof(aValidProof())
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
                historyProofMetrics,
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
                historyProofMetrics,
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
                historyProofMetrics,
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        final var proof = aValidProof();

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
                historyProofMetrics,
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
                historyProofMetrics,
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        final var restarted = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();
        given(weights.sourceNodeIds()).willReturn(Set.of(SELF_ID, OTHER_NODE_ID));
        given(writableHistoryStore.restartWrapsSigning(CONSTRUCTION_ID, Set.of(SELF_ID, OTHER_NODE_ID)))
                .willReturn(restarted);

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), null, writableHistoryStore, false, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore).restartWrapsSigning(CONSTRUCTION_ID, Set.of(SELF_ID, OTHER_NODE_ID));
        verify(writableHistoryStore, never()).failForReason(anyLong(), any());
    }

    @Test
    void advanceConstructionReturnsEarlyForIrrecoverableFailureAtStart() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .failureReason("irrecoverable")
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verifyNoMoreInteractions(writableHistoryStore, prover);
    }

    @Test
    void advanceConstructionReturnsEarlyWhenRetryBudgetExhausted() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .failureReason(RECOVERABLE_REASON)
                .wrapsRetryCount(DEFAULT_TSS_CONFIG.maxWrapsRetries())
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore, never()).restartWrapsSigning(anyLong(), any());
        verifyNoMoreInteractions(writableHistoryStore);
    }

    @Test
    void advanceConstructionPublishesKeyWhileWaitingForAssemblyBeforeGracePeriodEnds() {
        given(weights.numTargetNodesInSource()).willReturn(2);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(submissions.submitProofKeyPublication(any())).willReturn(CompletableFuture.completedFuture(null));

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(5), METADATA, writableHistoryStore, true, tssConfig);

        verify(submissions).submitProofKeyPublication(eq(keyPair.publicKey()));
        verify(writableHistoryStore, never()).setAssemblyTime(anyLong(), any());
    }

    @Test
    void advanceConstructionHandlesFailedProofKeyPublication() {
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(submissions.submitProofKeyPublication(any()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        assertDoesNotThrow(
                () -> subject.advanceConstruction(Instant.EPOCH, null, writableHistoryStore, true, tssConfig));

        verify(submissions).submitProofKeyPublication(eq(keyPair.publicKey()));
    }

    @Test
    void advanceConstructionReportsIntermediateWrapsStages() {
        assertStageForWrapsPhase(R2, HistoryProofMetrics.Stage.WRAPS_R2);
        assertStageForWrapsPhase(R3, HistoryProofMetrics.Stage.WRAPS_R3);
        assertStageForWrapsPhase(AGGREGATE, HistoryProofMetrics.Stage.WRAPS_AGGREGATE);
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        final var publication = new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH);

        subject.addProofKeyPublication(publication);

        // No exception and no interaction with weights (used by maybeUpdateForProofKey)
        verify(weights, never()).targetIncludes(anyLong());
    }

    @Test
    void addProofKeyPublicationIgnoresNonTargetNode() {
        final var publication = new ProofKeyPublication(OTHER_NODE_ID, PROOF_KEY_1, Instant.EPOCH);

        given(weights.targetIncludes(OTHER_NODE_ID)).willReturn(false);

        subject.addProofKeyPublication(publication);
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
                .targetProof(aValidProof())
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
                historyProofMetrics,
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
                .targetProof(aValidProof())
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        final var vote = HistoryProofVote.newBuilder().proof(aValidProof()).build();

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
    }

    @Test
    void addProofVoteStoresDirectProofVoteAndMayFinish() {
        final var proof = aValidProof();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);
        given(writableHistoryStore.completeProof(eq(CONSTRUCTION_ID), eq(proof)))
                .willReturn(construction);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(vote));
        verify(writableHistoryStore).completeProof(eq(CONSTRUCTION_ID), eq(proof));
        verify(historyService).onFinished(eq(writableHistoryStore), any(), any());
    }

    @Test
    void addProofVoteHandlesCongruentVotes() {
        final var proof = aValidProof();
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        final var congruentVote =
                HistoryProofVote.newBuilder().congruentNodeId(OTHER_NODE_ID).build();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightOf(OTHER_NODE_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(15L);
        given(writableHistoryStore.completeProof(eq(CONSTRUCTION_ID), any())).willReturn(construction);

        subject.addProofVote(SELF_ID, congruentVote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(congruentVote));
    }

    @Test
    void addProofVoteIgnoresDuplicateVote() {
        final var originalVote =
                HistoryProofVote.newBuilder().proof(aValidProof()).build();
        existingVotes.put(SELF_ID, originalVote);

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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        reset(writableHistoryStore, prover);

        subject.addProofVote(SELF_ID, originalVote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
        verify(prover, never()).observeProofVote(anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void addProofVoteIgnoresMalformedCongruentVote() {
        final var vote =
                HistoryProofVote.newBuilder().congruentNodeId(OTHER_NODE_ID).build();

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
        verify(prover, never()).observeProofVote(anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void addProofVoteStoresAggregatedSignatureVoteWithoutFinishingBelowThreshold() {
        final var proof = aggregatedSignatureProof();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(4L);
        given(weights.sourceWeightThreshold()).willReturn(5L);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(vote));
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.NOT_RECURSIVE));
    }

    @Test
    void addProofVoteCategorizesInvalidRecursiveVoteWithoutFinishing() {
        final var proof = recursiveProof("compressed", "uncompressed");
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        given(historyLibrary.verifyCompressedProof(
                        eq(Bytes.wrap("compressed").toByteArray()),
                        eq(Bytes.EMPTY.toByteArray()),
                        eq(Bytes.EMPTY.toByteArray())))
                .willReturn(false);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(15L);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(vote));
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID_RECURSIVE));
    }

    @Test
    void addProofVoteFinishesRecursiveProofWhenValidVotesReachThreshold() {
        final var higherNodeProof = recursiveProof("compressed-2", "uncompressed-2");
        final var lowerNodeProof = recursiveProof("compressed-1", "uncompressed-1");
        existingVotes.put(
                OTHER_NODE_ID,
                HistoryProofVote.newBuilder().proof(higherNodeProof).build());

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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        final var lowerNodeVote =
                HistoryProofVote.newBuilder().proof(lowerNodeProof).build();

        given(historyLibrary.verifyCompressedProof(
                        eq(Bytes.wrap("compressed-1").toByteArray()),
                        eq(Bytes.EMPTY.toByteArray()),
                        eq(Bytes.EMPTY.toByteArray())))
                .willReturn(true);
        given(historyLibrary.verifyCompressedProof(
                        eq(Bytes.wrap("compressed-2").toByteArray()),
                        eq(Bytes.EMPTY.toByteArray()),
                        eq(Bytes.EMPTY.toByteArray())))
                .willReturn(true);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightOf(OTHER_NODE_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(15L);
        given(writableHistoryStore.completeProof(eq(CONSTRUCTION_ID), eq(lowerNodeProof)))
                .willReturn(construction);

        subject.addProofVote(SELF_ID, lowerNodeVote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(lowerNodeVote));
        verify(writableHistoryStore).completeProof(eq(CONSTRUCTION_ID), eq(lowerNodeProof));
        verify(prover)
                .observeProofVote(eq(SELF_ID), eq(lowerNodeVote), eq(true), eq(ProofVoteCategory.VALID_RECURSIVE));
    }

    @Test
    void addProofVoteIgnoresCompletedWrapsExtensibleProofWhenWrapsEnabled() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetProof(recursiveProof("compressed", "uncompressed"))
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        given(tssConfig.wrapsEnabled()).willReturn(true);

        final var vote = HistoryProofVote.newBuilder()
                .proof(recursiveProof("later", "later-uncompressed"))
                .build();

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
        verify(prover, never()).observeProofVote(anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void advanceConstructionStartsAssemblyAfterGracePeriodWhenPublishedWeightMeetsThreshold() {
        given(weights.numTargetNodesInSource()).willReturn(2);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(weights.targetWeightOf(SELF_ID)).willReturn(7L);
        given(weights.targetWeightThreshold()).willReturn(5L);
        given(writableHistoryStore.setAssemblyTime(eq(CONSTRUCTION_ID), any())).willReturn(construction);

        subject.addProofKeyPublication(new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH));

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(20), METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).setAssemblyTime(eq(CONSTRUCTION_ID), any());
    }

    @Test
    void constructorReplaysWrapsMessagesAndSkipsLateProofKeyPublications() {
        keyPublications.add(new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH));
        keyPublications.add(new ProofKeyPublication(OTHER_NODE_ID, Bytes.wrap("late"), Instant.EPOCH.plusSeconds(11)));
        wrapsMessagePublications.add(
                new WrapsMessagePublication(SELF_ID, Bytes.EMPTY, R1, Instant.EPOCH.plusSeconds(2)));

        given(weights.targetIncludes(SELF_ID)).willReturn(true);

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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        given(weights.numTargetNodesInSource()).willReturn(2);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(5), METADATA, writableHistoryStore, true, tssConfig);

        verify(prover).replayWrapsSigningMessage(eq(CONSTRUCTION_ID), eq(wrapsMessagePublications.getFirst()));
        verify(writableHistoryStore, never()).setAssemblyTime(anyLong(), any());
    }

    @Test
    void constructorUsesSourceProofKeysWhenSourceProofPresent() {
        final var sourceProof = HistoryProof.newBuilder()
                .targetProofKeys(new com.hedera.hapi.node.state.history.ProofKey(SELF_ID, PROOF_KEY_1))
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
                sourceProof,
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        verify(proverFactory)
                .create(
                        eq(SELF_ID),
                        eq(DEFAULT_TSS_CONFIG),
                        eq(keyPair),
                        eq(sourceProof),
                        eq(weights),
                        eq(Map.of(SELF_ID, PROOF_KEY_1)),
                        any(),
                        eq(historyLibrary),
                        eq(submissions));
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

    private static HistoryProof aValidProof() {
        return HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .build();
    }

    private static HistoryProof aggregatedSignatureProof() {
        return HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.newBuilder()
                        .aggregatedNodeSignatures(new AggregatedNodeSignatures(
                                Bytes.wrap("aggSig"), new ArrayList<>(List.of(SELF_ID)), PROOF_KEY_1)))
                .build();
    }

    private static HistoryProof recursiveProof(final String compressedProof, final String uncompressedProof) {
        return HistoryProof.newBuilder()
                .uncompressedWrapsProof(Bytes.wrap(uncompressedProof))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap(compressedProof)))
                .build();
    }

    private void assertStageForWrapsPhase(
            final com.hedera.hapi.node.state.history.WrapsPhase phase, final HistoryProofMetrics.Stage stage) {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(phase).build())
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
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, tssConfig);

        verify(historyProofMetrics, times(2)).observeStage(CONSTRUCTION_ID, stage, now);
        reset(historyProofMetrics, prover, writableHistoryStore);
    }

    private void setField(String name, Object value) throws Exception {
        final var field = ProofControllerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(subject, value);
    }
}
