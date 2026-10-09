// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
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
import com.hedera.hapi.node.state.history.History;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProofControllerImplTest {

    private static final long SELF_ID = 1L;
    private static final long OTHER_NODE_ID = 2L;
    private static final long CONSTRUCTION_ID = 100L;
    private static final Bytes METADATA = Bytes.wrap("meta");
    private static final Bytes PROOF_KEY_1 = Bytes.wrap("pk1");
    private static final Bytes ROSTER_HASH = Bytes.wrap("roster-hash");
    private static final Bytes TARGET_BOOK_HASH = Bytes.wrap("target-book-hash");
    private static final Bytes GROUNDED_LEDGER_ID = Bytes.wrap("grounded-ledger-id");
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
        assertTrue(subject.isStillInProgress());
    }

    @Test
    void isStillInProgressFalseWhenHasTargetProof() {
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

        assertFalse(subject.isStillInProgress());
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

        assertFalse(subject.isStillInProgress());
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
    void constructorCreatesNoProverForCompletedConstruction() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetProof(recursiveProof("compressed", "uncompressed"))
                .build();
        reset(proverFactory);

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

        verifyNoMoreInteractions(proverFactory);
        assertFalse(subject.isStillInProgress());
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

    @ParameterizedTest(name = "isActive={0}")
    @ValueSource(booleans = {false, true})
    void setsAssemblyTimeRegardlessOfNodeActiveStatus(final boolean isActive) {
        // With every target node's proof key present (none expected here) assembly starts now, so the
        // assembly-time write uses the consensus timestamp regardless of ACTIVE status. This branch returns
        // right after that write and never reaches the publish step in either state, so the never-publish
        // check below is incidental — the ACTIVE-gating of proof-key publication itself is proven by
        // advanceConstructionPublishesKeyWhenMetadataMissingAndActive / ...DoesNotPublishKeyWhenInactive.
        given(weights.numTargetNodesInSource()).willReturn(0);
        given(writableHistoryStore.setAssemblyTime(CONSTRUCTION_ID, Instant.EPOCH.plusSeconds(1)))
                .willReturn(construction);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, isActive, tssConfig);

        verify(writableHistoryStore).setAssemblyTime(CONSTRUCTION_ID, Instant.EPOCH.plusSeconds(1));
        verify(submissions, never()).submitProofKeyPublication(any());
    }

    @Test
    void advanceConstructionDelegatesToProverWhenAssemblyStartedAndInactive() {
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
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any(), anyBoolean()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(construction);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, false, tssConfig);

        verify(prover).advance(any(), eq(construction), eq(METADATA), any(), eq(tssConfig), any(), eq(false));
        verify(writableHistoryStore).getConstructionOrThrow(CONSTRUCTION_ID);
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
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any(), anyBoolean()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).getLedgerId();
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any(), eq(true));
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
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Completed(proof));
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).getLedgerId();
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any(), eq(true));
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
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(reason));
        given(writableHistoryStore.failForReason(CONSTRUCTION_ID, reason)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, tssConfig);

        verify(writableHistoryStore).getLedgerId();
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any(), eq(true));
        verify(writableHistoryStore).failForReason(CONSTRUCTION_ID, reason);
    }

    @ParameterizedTest(name = "isActive={0}")
    @ValueSource(booleans = {false, true})
    void finishesProofRegardlessOfNodeActiveStatus(final boolean isActive) {
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
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Completed(proof));
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, isActive, tssConfig);

        // The Completed-outcome commit write happens regardless of ACTIVE status; isActive only rides
        // into prover.advance as the can-submit flag.
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any(), eq(isActive));
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
        // The handoff to the history service is part of the same ungated path
        verify(writableHistoryStore).getLedgerId();
        verify(historyService).onFinished(eq(writableHistoryStore), eq(construction), any());
    }

    @ParameterizedTest(name = "isActive={0}")
    @ValueSource(booleans = {false, true})
    void failsConstructionRegardlessOfNodeActiveStatus(final boolean isActive) {
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
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(reason));
        given(writableHistoryStore.failForReason(CONSTRUCTION_ID, reason)).willReturn(construction);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, isActive, tssConfig);

        // The Failed-outcome commit write happens regardless of ACTIVE status.
        verify(prover).advance(eq(now), eq(construction), eq(METADATA), any(), eq(tssConfig), any(), eq(isActive));
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
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID))))
                .willReturn(restarted);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        verify(writableHistoryStore, never()).failForReason(anyLong(), any());
    }

    @Test
    void advanceConstructionRestartsOnRecoverableWrapsFailureWhenInactive() {
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
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), eq(false)))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID))))
                .willReturn(restarted);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, false, DEFAULT_TSS_CONFIG);

        verify(prover)
                .advance(eq(now), eq(construction), eq(METADATA), any(), eq(DEFAULT_TSS_CONFIG), any(), eq(false));
        verify(writableHistoryStore)
                .restartWrapsSigning(CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
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
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID))))
                .willReturn(restarted);

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), null, writableHistoryStore, false, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
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
        final var proof = recursiveProof("compressed", "uncompressed");
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
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
        final var proof = recursiveProof("compressed", "uncompressed");
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

        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightOf(OTHER_NODE_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(15L);
        given(writableHistoryStore.completeProof(eq(CONSTRUCTION_ID), any())).willReturn(construction);

        subject.addProofVote(SELF_ID, congruentVote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(congruentVote));
        verify(writableHistoryStore).completeProof(eq(CONSTRUCTION_ID), eq(proof));
        verify(prover).observeProofVote(eq(SELF_ID), eq(congruentVote), eq(true), eq(ProofVoteCategory.VALID));
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
    void addProofVoteCategorizesVoteWithoutWrapsProofAsInvalidEvenWithThresholdWeight() {
        final var proof = aggregatedSignatureProof();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(vote));
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
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
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
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
        verify(prover).observeProofVote(eq(SELF_ID), eq(lowerNodeVote), eq(true), eq(ProofVoteCategory.VALID));
    }

    @Test
    void recursiveProofValidationCacheIncludesLedgerAndMetadataContext() throws Exception {
        final var proof = recursiveProof("compressed", "uncompressed");
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        final var ledgerId1 = Bytes.wrap("ledger-1");
        final var ledgerId2 = Bytes.wrap("ledger-2");
        final var metadata1 = Bytes.wrap("metadata-1");
        final var metadata2 = Bytes.wrap("metadata-2");

        given(writableHistoryStore.getLedgerId()).willReturn(ledgerId1, ledgerId2);
        given(weights.sourceWeightThreshold()).willReturn(1L);

        setField("targetMetadata", metadata1);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        setField("targetMetadata", metadata2);
        subject.addProofVote(OTHER_NODE_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(historyLibrary)
                .verifyCompressedProof(
                        aryEq(Bytes.wrap("compressed").toByteArray()),
                        aryEq(ledgerId1.toByteArray()),
                        aryEq(metadata1.toByteArray()));
        verify(historyLibrary)
                .verifyCompressedProof(
                        aryEq(Bytes.wrap("compressed").toByteArray()),
                        aryEq(ledgerId2.toByteArray()),
                        aryEq(metadata2.toByteArray()));
    }

    @Test
    void recursiveProofValidationCacheIsClearedOnRetry() throws Exception {
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
        final var proof = recursiveProof("compressed", "uncompressed");
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        final var ledgerId = Bytes.wrap("ledger");
        final var metadata = Bytes.wrap("metadata");
        final var restarted = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();

        given(writableHistoryStore.getLedgerId()).willReturn(ledgerId);
        given(weights.sourceWeightThreshold()).willReturn(1L);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), eq(true)))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID))))
                .willReturn(restarted);

        setField("targetMetadata", metadata);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, DEFAULT_TSS_CONFIG);
        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), metadata, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        subject.addProofVote(OTHER_NODE_ID, vote, Instant.EPOCH, writableHistoryStore, DEFAULT_TSS_CONFIG);

        verify(historyLibrary, times(2))
                .verifyCompressedProof(
                        aryEq(Bytes.wrap("compressed").toByteArray()),
                        aryEq(ledgerId.toByteArray()),
                        aryEq(metadata.toByteArray()));
    }

    @Test
    void addProofVoteIgnoresVotesOnceProofIsFinished() {
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

        final var vote = HistoryProofVote.newBuilder()
                .proof(recursiveProof("later", "later-uncompressed"))
                .build();

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
        verify(prover, never()).observeProofVote(anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void groundingConstructionValidatesProofAgainstLedgerIdOfExpectedHistory() throws Exception {
        givenGroundingSubject();
        final var expectedHistory = new History(TARGET_BOOK_HASH, METADATA);
        final var proof = recursiveProof("compressed", "uncompressed", expectedHistory);
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(historyLibrary.ledgerIdOf(expectedHistory)).willReturn(GROUNDED_LEDGER_ID);
        given(historyLibrary.verifyCompressedProof(
                        aryEq(Bytes.wrap("compressed").toByteArray()),
                        aryEq(GROUNDED_LEDGER_ID.toByteArray()),
                        aryEq(METADATA.toByteArray())))
                .willReturn(true);
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 10L)));
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        setField("targetMetadata", METADATA);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        // The ledger id in state (if any) is irrelevant to a proof that grounds a new chain of trust
        verify(writableHistoryStore, never()).getLedgerId();
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(true), eq(ProofVoteCategory.VALID));
    }

    @Test
    void groundingConstructionRejectsProofOfUnexpectedHistoryWithoutVerifyingIt() throws Exception {
        givenGroundingSubject();
        final var expectedHistory = new History(TARGET_BOOK_HASH, METADATA);
        final var unexpectedHistory = new History(Bytes.wrap("SOME_OTHER_BOOK_HASH"), METADATA);
        final var vote = HistoryProofVote.newBuilder()
                .proof(recursiveProof("compressed", "uncompressed", unexpectedHistory))
                .build();
        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(historyLibrary.ledgerIdOf(expectedHistory)).willReturn(GROUNDED_LEDGER_ID);
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 10L)));
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);

        setField("targetMetadata", METADATA);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(historyLibrary, never()).verifyCompressedProof(any(), any(), any());
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
    }

    @Test
    void groundingConstructionCannotValidateAnyProofWithoutMetadata() {
        givenGroundingSubject();
        final var vote = HistoryProofVote.newBuilder()
                .proof(recursiveProof("compressed", "uncompressed", new History(TARGET_BOOK_HASH, Bytes.EMPTY)))
                .build();
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(historyLibrary, never()).hashAddressBook(any());
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
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
    void constructorReplaysCongruentVoteChainRegardlessOfMapOrder() {
        // Regression for #26524: persisted votes come back from state in HashMap iteration order, NOT the consensus
        // order in which they were cast. A congruent vote replayed before the explicit vote it references must still
        // be counted; otherwise a reconnecting node ends up with less counted weight than the nodes that never
        // restarted and can diverge (ISS) when a later vote completes the proof only on the continuously running
        // nodes. Pin a deliberately hostile order with a LinkedHashMap -- each congruent vote precedes its referent,
        // and the chain node 0 -> node 1 -> node 2 (explicit) is only rebuilt if replay resolves dependencies
        // iteratively rather than in map order -- so the test does not depend on the current JDK's HashMap bucket
        // order.
        final var proof = recursiveProof("compressed", "uncompressed");
        final Map<Long, HistoryProofVote> hostileOrderVotes = new LinkedHashMap<>();
        hostileOrderVotes.put(
                0L, HistoryProofVote.newBuilder().congruentNodeId(1L).build());
        hostileOrderVotes.put(
                1L, HistoryProofVote.newBuilder().congruentNodeId(2L).build());
        hostileOrderVotes.put(2L, HistoryProofVote.newBuilder().proof(proof).build());

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
                hostileOrderVotes,
                historyService,
                historyLibrary,
                proverFactory,
                null,
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);

        // Nodes 0, 1, and 2 must all be counted (weight 30). With a threshold of 35 the proof is not yet complete,
        // but one more congruent vote (node 3) crosses it. Had node 0 or node 1 been dropped during replay, the tally
        // would be short of the threshold and completeProof would never be called.
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightOf(0L)).willReturn(10L);
        given(weights.sourceWeightOf(1L)).willReturn(10L);
        given(weights.sourceWeightOf(2L)).willReturn(10L);
        given(weights.sourceWeightOf(3L)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(35L);
        given(writableHistoryStore.completeProof(eq(CONSTRUCTION_ID), eq(proof)))
                .willReturn(construction);

        final var thresholdCrossingVote =
                HistoryProofVote.newBuilder().congruentNodeId(2L).build();
        subject.addProofVote(3L, thresholdCrossingVote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).completeProof(eq(CONSTRUCTION_ID), eq(proof));
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

    private static HistoryProof recursiveProof(
            final String compressedProof, final String uncompressedProof, final History targetHistory) {
        return recursiveProof(compressedProof, uncompressedProof)
                .copyBuilder()
                .targetHistory(targetHistory)
                .build();
    }

    /**
     * Uses a subject whose construction grounds a chain of trust; i.e., has the same source and target roster.
     */
    private void givenGroundingSubject() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .sourceRosterHash(ROSTER_HASH)
                .targetRosterHash(ROSTER_HASH)
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(AGGREGATE))
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
        given(prover.advance(any(), any(), any(), any(), eq(tssConfig), any(), anyBoolean()))
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
