// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.history.HistoryLibrary.MAX_ADDRESS_BOOK_SIZE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.AggregatedNodeSignatures;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.hints.KeyProvenanceSigner;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.LongStream;
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
    private static final long THIRD_NODE_ID = 3L;
    private static final long CONSTRUCTION_ID = 100L;
    private static final Bytes METADATA = Bytes.wrap("meta");
    private static final Bytes PROOF_KEY_1 = Bytes.wrap("pk1");
    private static final Bytes ROSTER_HASH = Bytes.wrap("roster-hash");
    private static final Bytes TARGET_BOOK_HASH = Bytes.wrap("target-book-hash");
    private static final Bytes GROUNDED_LEDGER_ID = Bytes.wrap("grounded-ledger-id");
    private static final History TARGET_HISTORY = new History(TARGET_BOOK_HASH, METADATA);
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
    private KeyProvenanceSigner sourceSigner;

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

        // Like the network state, the store holds whatever construction the test last set up
        lenient()
                .when(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID))
                .thenAnswer(ignore -> construction);
        // Votes count only from source nodes, as when a controller is rebuilt from state; unless a test says
        // otherwise, every node id these tests vote with is a source node
        lenient()
                .when(weights.sourceNodeIds())
                .thenReturn(new TreeSet<>(List.of(0L, SELF_ID, OTHER_NODE_ID, THIRD_NODE_ID)));

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
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
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

        verify(writableHistoryStore).getConstructionOrThrow(CONSTRUCTION_ID);
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
        verify(writableHistoryStore, times(2)).getConstructionOrThrow(CONSTRUCTION_ID);
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
        verify(writableHistoryStore, times(2)).getConstructionOrThrow(CONSTRUCTION_ID);
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
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();

        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null))
                .willReturn(restarted);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null);
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
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();

        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), eq(false)))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null))
                .willReturn(restarted);

        final var now = Instant.EPOCH.plusSeconds(1);
        subject.advanceConstruction(now, METADATA, writableHistoryStore, false, DEFAULT_TSS_CONFIG);

        verify(prover)
                .advance(eq(now), eq(construction), eq(METADATA), any(), eq(DEFAULT_TSS_CONFIG), any(), eq(false));
        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null);
        verify(writableHistoryStore, never()).failForReason(anyLong(), any());
    }

    @Test
    void advanceConstructionRecoversFailedConstructionAtStart() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
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
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null))
                .willReturn(restarted);

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), null, writableHistoryStore, false, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null);
        verify(writableHistoryStore, never()).failForReason(anyLong(), any());
    }

    @Test
    void advanceConstructionReturnsEarlyForIrrecoverableFailureAtStart() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
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

        verify(writableHistoryStore).getConstructionOrThrow(CONSTRUCTION_ID);
        verifyNoMoreInteractions(writableHistoryStore, prover);
    }

    @Test
    void advanceConstructionReturnsEarlyWhenRetryBudgetExhausted() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
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

        verify(writableHistoryStore, never()).restartWrapsSigning(anyLong(), any(), any(), any());
        verify(writableHistoryStore).getConstructionOrThrow(CONSTRUCTION_ID);
        verifyNoMoreInteractions(writableHistoryStore);
    }

    @Test
    void retryExcludesMissingNodesWhenTheRestCanStillCompleteR1() {
        givenFailedAttemptWithSourceWeights(Map.of(SELF_ID, 2L, OTHER_NODE_ID, 1L), Set.of(SELF_ID, OTHER_NODE_ID));

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID,
                        new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)),
                        new TreeSet<>(List.of(OTHER_NODE_ID)),
                        Instant.EPOCH.plusSeconds(1).plus(DEFAULT_TSS_CONFIG.wrapsMessageGracePeriod()));
    }

    @Test
    void retryKeepsMissingNodesWhenTheRestCannotCompleteR1() {
        givenFailedAttemptWithSourceWeights(Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L), Set.of(SELF_ID, OTHER_NODE_ID));

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null);
    }

    @Test
    void retryDoesNotCountNodesWithoutProofKeysTowardR1() {
        // Node 3 has weight but no proof key, so it can never publish an R1 message
        givenFailedAttemptWithSourceWeights(
                Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L, THIRD_NODE_ID, 1L), Set.of(SELF_ID, OTHER_NODE_ID));

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID,
                        new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID, THIRD_NODE_ID)),
                        new TreeSet<>(),
                        null);
    }

    @Test
    void retryAfterPersistedFailureKeepsEarlierExclusions() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .failureReason(RECOVERABLE_REASON)
                .wrapsExcludedNodeIds(List.of(OTHER_NODE_ID))
                .build();
        subject = newSubject(null);
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(eq(CONSTRUCTION_ID), any(), any(), any()))
                .willReturn(HistoryProofConstruction.newBuilder()
                        .constructionId(CONSTRUCTION_ID)
                        .wrapsSigningState(WrapsSigningState.newBuilder().build())
                        .wrapsRetryCount(1)
                        .build());

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), null, writableHistoryStore, false, DEFAULT_TSS_CONFIG);

        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID,
                        new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)),
                        new TreeSet<>(List.of(OTHER_NODE_ID)),
                        Instant.EPOCH.plusSeconds(1).plus(DEFAULT_TSS_CONFIG.wrapsMessageGracePeriod()));
    }

    @Test
    void ignoresWrapsMessagesFromExcludedNodes() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(R1).build())
                .wrapsExcludedNodeIds(List.of(OTHER_NODE_ID))
                .build();
        subject = newSubject(null);

        assertFalse(subject.addWrapsMessagePublication(
                new WrapsMessagePublication(OTHER_NODE_ID, Bytes.wrap("r1"), R1, Instant.EPOCH), writableHistoryStore));
        verify(prover, never()).addWrapsSigningMessage(anyLong(), any(), any());
    }

    @Test
    void advancesFromTheConstructionInStateRatherThanAnOlderCopy() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(R3).build())
                .build();
        subject = newSubject(null);
        // Handling the last R3 message moved the construction in state to AGGREGATE after the controller last
        // advanced, so its copy is older than what a controller rebuilt from state would see
        final var inState = construction
                .copyBuilder()
                .wrapsSigningState(
                        WrapsSigningState.newBuilder().phase(AGGREGATE).build())
                .build();
        construction = inState;
        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), anyBoolean()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(prover).advance(any(), eq(inState), eq(METADATA), any(), eq(DEFAULT_TSS_CONFIG), any(), eq(true));
    }

    @Test
    void retryAfterR1TimesOutLetsEveryNodeSignAgain() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(R1).build())
                .wrapsExcludedNodeIds(List.of(OTHER_NODE_ID))
                .build();
        subject = newSubject(null);
        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(
                        WrapsHistoryProver.R1_TIMEOUT_FAILURE_PREFIX + "[" + SELF_ID + "]"));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(eq(CONSTRUCTION_ID), any(), any(), any()))
                .willReturn(HistoryProofConstruction.newBuilder()
                        .constructionId(CONSTRUCTION_ID)
                        .wrapsSigningState(WrapsSigningState.newBuilder().build())
                        .wrapsRetryCount(1)
                        .build());

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        // With no node excluded, the restarted R1 needs no grace period
        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                WrapsHistoryProver.INVALID_FROZEN_SIGNERS_FAILURE_PREFIX,
                WrapsHistoryProver.INFEASIBLE_EXCLUSIONS_FAILURE_PREFIX + " [2]"
            })
    void retryAfterAFailureFromOlderSigningStateLetsEveryNodeSignAgain(final String reason) {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .wrapsSigningState(
                        WrapsSigningState.newBuilder().phase(AGGREGATE).build())
                .wrapsExcludedNodeIds(List.of(OTHER_NODE_ID))
                .build();
        subject = newSubject(null);
        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(reason));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(eq(CONSTRUCTION_ID), any(), any(), any()))
                .willReturn(HistoryProofConstruction.newBuilder()
                        .constructionId(CONSTRUCTION_ID)
                        .wrapsSigningState(WrapsSigningState.newBuilder().build())
                        .wrapsRetryCount(1)
                        .build());

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        // The exclusions may have come from the older rule too, so the fresh attempt starts without any
        verify(writableHistoryStore)
                .restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null);
        verify(writableHistoryStore, never()).failForReason(anyLong(), any());
    }

    @Test
    void votesFromNodesOutsideTheSourceGenerationAreIgnoredAsWhenRebuiltFromState() throws Exception {
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        lenient().when(historyLibrary.hashAddressBook(any())).thenReturn(TARGET_BOOK_HASH.toByteArray());
        setField("targetMetadata", METADATA);
        final var outsiderVote = HistoryProofVote.newBuilder()
                .proof(recursiveProof("compressed", "uncompressed", TARGET_HISTORY))
                .build();
        final var congruentToOutsider =
                HistoryProofVote.newBuilder().congruentNodeId(THIRD_NODE_ID).build();

        subject.addProofVote(
                THIRD_NODE_ID, outsiderVote, Instant.EPOCH.plusSeconds(1), writableHistoryStore, DEFAULT_TSS_CONFIG);
        subject.addProofVote(
                SELF_ID, congruentToOutsider, Instant.EPOCH.plusSeconds(2), writableHistoryStore, DEFAULT_TSS_CONFIG);

        // A controller rebuilt from state never loads node3's vote, so live handling must not count, persist, or
        // resolve congruent votes against it either
        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover, never()).observeProofVote(anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void failureAfterTheRetryBudgetKeepsTheExclusionsARetryWouldUse() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(R2).build())
                .wrapsRetryCount(DEFAULT_TSS_CONFIG.maxWrapsRetries())
                .build();
        subject = newSubject(HistoryProof.newBuilder()
                .targetNodeWeights(
                        HistoryProofAddressBooks.weightsFrom(new TreeMap<>(Map.of(SELF_ID, 2L, OTHER_NODE_ID, 1L))))
                .targetProofKeys(List.of(
                        new ProofKey(SELF_ID, PROOF_KEY_1), new ProofKey(OTHER_NODE_ID, Bytes.wrap("other key"))))
                .build());
        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON, Set.of(OTHER_NODE_ID)));
        given(weights.sourceNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 2L, OTHER_NODE_ID, 1L)));
        given(writableHistoryStore.failForReason(CONSTRUCTION_ID, RECOVERABLE_REASON))
                .willReturn(construction
                        .copyBuilder()
                        .failureReason(RECOVERABLE_REASON)
                        .build());

        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        final var inOrder = inOrder(writableHistoryStore);
        inOrder.verify(writableHistoryStore).excludeFromWrapsSigning(CONSTRUCTION_ID, Set.of(OTHER_NODE_ID));
        inOrder.verify(writableHistoryStore).failForReason(CONSTRUCTION_ID, RECOVERABLE_REASON);
        verify(writableHistoryStore, never()).restartWrapsSigning(anyLong(), any(), any(), any());
    }

    @Test
    void ignoresWrapsMessagesFromNodesExcludedSinceTheControllerLastAdvanced() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(R1).build())
                .build();
        subject = newSubject(null);
        // The prover excluded the node while handling an earlier transaction
        construction = construction
                .copyBuilder()
                .wrapsExcludedNodeIds(List.of(OTHER_NODE_ID))
                .build();

        assertFalse(subject.addWrapsMessagePublication(
                new WrapsMessagePublication(OTHER_NODE_ID, Bytes.wrap("r1"), R1, Instant.EPOCH), writableHistoryStore));
        verify(prover, never()).addWrapsSigningMessage(anyLong(), any(), any());
    }

    @Test
    void republishesItsProofKeyWhenTheKeyInStateIsNotItsOwn() {
        // For example, after this node lost its key file and created a new key
        keyPublications.add(new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH));
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        subject = newSubject(null);
        given(submissions.submitProofKeyPublication(any())).willReturn(CompletableFuture.completedFuture(null));

        subject.advanceConstruction(Instant.EPOCH, null, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(submissions).submitProofKeyPublication(keyPair.publicKey());
    }

    @Test
    void doesNotRepublishItsProofKeyWhenTheKeyInStateIsItsOwn() {
        keyPublications.add(new ProofKeyPublication(SELF_ID, keyPair.publicKey(), Instant.EPOCH));
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        subject = newSubject(null);

        subject.advanceConstruction(Instant.EPOCH, null, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(submissions, never()).submitProofKeyPublication(any());
    }

    private void givenFailedAttemptWithSourceWeights(
            @NonNull final Map<Long, Long> sourceWeights, @NonNull final Set<Long> nodeIdsWithProofKeys) {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH))
                .wrapsSigningState(WrapsSigningState.newBuilder().phase(R2).build())
                .build();
        subject = newSubject(HistoryProof.newBuilder()
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(new TreeMap<>(sourceWeights)))
                .targetProofKeys(nodeIdsWithProofKeys.stream()
                        .sorted()
                        .map(nodeId -> new ProofKey(nodeId, Bytes.wrap("pk-" + nodeId)))
                        .toList())
                .build());
        given(writableHistoryStore.getLedgerId()).willReturn(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON, Set.of(OTHER_NODE_ID)));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(sourceWeights.keySet()));
        given(weights.sourceNodeWeights()).willReturn(new TreeMap<>(sourceWeights));
        given(writableHistoryStore.restartWrapsSigning(eq(CONSTRUCTION_ID), any(), any(), any()))
                .willReturn(HistoryProofConstruction.newBuilder()
                        .constructionId(CONSTRUCTION_ID)
                        .wrapsSigningState(WrapsSigningState.newBuilder().build())
                        .wrapsRetryCount(1)
                        .build());
    }

    private ProofControllerImpl newSubject(@Nullable final HistoryProof sourceProof) {
        return new ProofControllerImpl(
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
    }

    @Test
    void advanceConstructionPublishesKeyWhileWaitingForAssemblyBeforeGracePeriodEnds() {
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L)));
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(submissions.submitProofKeyPublication(any())).willReturn(CompletableFuture.completedFuture(null));

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(5), METADATA, writableHistoryStore, true, tssConfig);

        verify(submissions).submitProofKeyPublication(eq(keyPair.publicKey()));
        verify(writableHistoryStore, never()).setAssemblyTime(anyLong(), any());
    }

    @Test
    void assemblyWaitsForAllTargetKeysRatherThanOnlyTheSparseSourceMembership() {
        final var sourceWeights = new TreeMap<>(Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L));
        final var targetWeights = new TreeMap<>(Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L, THIRD_NODE_ID, 1L));
        subject = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                new RosterTransitionWeights(sourceWeights, targetWeights),
                executor,
                submissions,
                machine,
                List.of(),
                List.of(),
                Map.of(),
                historyService,
                historyLibrary,
                (s, t, k, p, w, r, x, l, m) -> prover,
                null,
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);
        subject.addProofKeyPublication(new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH));
        subject.addProofKeyPublication(new ProofKeyPublication(OTHER_NODE_ID, Bytes.wrap("second key"), Instant.EPOCH));

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(5), METADATA, writableHistoryStore, false, tssConfig);

        verify(writableHistoryStore, never()).setAssemblyTime(anyLong(), any());
        subject.addProofKeyPublication(new ProofKeyPublication(THIRD_NODE_ID, Bytes.wrap("third key"), Instant.EPOCH));
        given(writableHistoryStore.setAssemblyTime(CONSTRUCTION_ID, Instant.EPOCH.plusSeconds(6)))
                .willReturn(construction);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(6), METADATA, writableHistoryStore, false, tssConfig);

        verify(writableHistoryStore).setAssemblyTime(CONSTRUCTION_ID, Instant.EPOCH.plusSeconds(6));
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
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 1L)));

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
    void addWrapsMessagePublicationDelegatesToProverOtherwise() throws Exception {
        construction = construction
                .copyBuilder()
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();
        setField("construction", construction);
        setField("targetMetadata", METADATA);
        final var publication = new WrapsMessagePublication(SELF_ID, Bytes.EMPTY, R1, Instant.EPOCH);

        given(prover.addWrapsSigningMessage(eq(CONSTRUCTION_ID), eq(publication), eq(writableHistoryStore)))
                .willReturn(true);

        final var result = subject.addWrapsMessagePublication(publication, writableHistoryStore);

        assertTrue(result);
        verify(prover).addWrapsSigningMessage(eq(CONSTRUCTION_ID), eq(publication), eq(writableHistoryStore));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    void wrapsMessagesWaitUntilAfterTheFrozenKeyCutoff(final long nanosFromCutoff) throws Exception {
        final var cutoff = Instant.EPOCH.plusSeconds(10);
        construction = construction
                .copyBuilder()
                .assemblyStartTime(asTimestamp(cutoff))
                .proofKeyCutoffTime(asTimestamp(cutoff))
                .build();
        subject = newSubject(null);
        setField("targetMetadata", METADATA);
        final var premature =
                new WrapsMessagePublication(SELF_ID, Bytes.wrap("premature R1"), R1, cutoff.plusNanos(nanosFromCutoff));

        assertFalse(subject.addWrapsMessagePublication(premature, writableHistoryStore));
        verify(prover, never()).addWrapsSigningMessage(anyLong(), any(), any());

        final var afterCutoff = new WrapsMessagePublication(SELF_ID, Bytes.wrap("R1"), R1, cutoff.plusNanos(1));
        given(prover.addWrapsSigningMessage(CONSTRUCTION_ID, afterCutoff, writableHistoryStore))
                .willReturn(true);

        assertTrue(subject.addWrapsMessagePublication(afterCutoff, writableHistoryStore));
        verify(prover).addWrapsSigningMessage(CONSTRUCTION_ID, afterCutoff, writableHistoryStore);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    void proofVotesWaitUntilAfterTheFrozenKeyCutoff(final long nanosFromCutoff) throws Exception {
        final var cutoff = Instant.EPOCH.plusSeconds(10);
        construction = construction
                .copyBuilder()
                .assemblyStartTime(asTimestamp(cutoff))
                .proofKeyCutoffTime(asTimestamp(cutoff))
                .build();
        subject = newSubject(null);
        givenTargetHistory();
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY);
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        subject.addProofVote(SELF_ID, vote, cutoff.plusNanos(nanosFromCutoff), writableHistoryStore, tssConfig);

        verify(writableHistoryStore, never()).addProofVote(anyLong(), anyLong(), any());
        verify(prover, never()).observeProofVote(anyLong(), any(), anyBoolean(), any());
        verify(historyLibrary, never()).verifyCompressedProof(any(), any(), any());
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        subject.addProofVote(SELF_ID, vote, cutoff.plusNanos(1), writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(SELF_ID, CONSTRUCTION_ID, vote);
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
        verify(prover).observeProofVote(SELF_ID, vote, true, ProofVoteCategory.VALID);
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
    void addProofVoteStoresDirectProofVoteAndMayFinish() throws Exception {
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY);
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        givenTargetHistory();
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
    void addProofVoteHandlesCongruentVotes() throws Exception {
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY);
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

        givenTargetHistory();
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
    void addProofVoteCategorizesVoteWithoutWrapsProofAsInvalidEvenWithThresholdWeight() throws Exception {
        final var proof = aggregatedSignatureProof()
                .copyBuilder()
                .targetHistory(TARGET_HISTORY)
                .build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        givenTargetHistory();
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(vote));
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
    }

    @Test
    void addProofVoteCategorizesInvalidRecursiveVoteWithoutFinishing() throws Exception {
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY);
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        givenTargetHistory();
        given(historyLibrary.verifyCompressedProof(
                        eq(Bytes.wrap("compressed").toByteArray()),
                        eq(Bytes.EMPTY.toByteArray()),
                        eq(METADATA.toByteArray())))
                .willReturn(false);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(15L);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(writableHistoryStore).addProofVote(eq(SELF_ID), eq(CONSTRUCTION_ID), eq(vote));
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(eq(SELF_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
    }

    @Test
    void addProofVoteFinishesRecursiveProofWhenValidVotesReachThreshold() throws Exception {
        final var higherNodeProof = recursiveProof("compressed-2", "uncompressed-2", TARGET_HISTORY);
        final var lowerNodeProof = recursiveProof("compressed-1", "uncompressed-1", TARGET_HISTORY);
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

        givenTargetHistory();
        given(historyLibrary.verifyCompressedProof(
                        eq(Bytes.wrap("compressed-1").toByteArray()),
                        eq(Bytes.EMPTY.toByteArray()),
                        eq(METADATA.toByteArray())))
                .willReturn(true);
        given(historyLibrary.verifyCompressedProof(
                        eq(Bytes.wrap("compressed-2").toByteArray()),
                        eq(Bytes.EMPTY.toByteArray()),
                        eq(METADATA.toByteArray())))
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
        final var metadata1 = Bytes.wrap("metadata-1");
        final var metadata2 = Bytes.wrap("metadata-2");
        final var proof = recursiveProof("compressed", "uncompressed", new History(TARGET_BOOK_HASH, metadata1));
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        final var ledgerId1 = Bytes.wrap("ledger-1");
        final var ledgerId2 = Bytes.wrap("ledger-2");

        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(historyLibrary.verifyCompressedProof(any(), any(), aryEq(metadata1.toByteArray())))
                .willReturn(true);
        given(writableHistoryStore.getLedgerId()).willReturn(ledgerId1, ledgerId2);
        given(weights.sourceWeightThreshold()).willReturn(1L);

        setField("targetMetadata", metadata1);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);
        subject.addProofVote(OTHER_NODE_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        // The proof commits to a history with the first metadata, so it is invalid for the second (and is only
        // found invalid if its validity for the first metadata is not reused)
        setField("targetMetadata", metadata2);
        subject.addProofVote(3L, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(historyLibrary)
                .verifyCompressedProof(
                        aryEq(Bytes.wrap("compressed").toByteArray()),
                        aryEq(ledgerId1.toByteArray()),
                        aryEq(metadata1.toByteArray()));
        verify(historyLibrary)
                .verifyCompressedProof(
                        aryEq(Bytes.wrap("compressed").toByteArray()),
                        aryEq(ledgerId2.toByteArray()),
                        aryEq(metadata1.toByteArray()));
        verify(historyLibrary, never()).verifyCompressedProof(any(), any(), aryEq(metadata2.toByteArray()));
        verify(prover).observeProofVote(eq(3L), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
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
        final var ledgerId = Bytes.wrap("ledger");
        final var metadata = Bytes.wrap("metadata");
        final var proof = recursiveProof("compressed", "uncompressed", new History(TARGET_BOOK_HASH, metadata));
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        final var restarted = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
                .wrapsSigningState(WrapsSigningState.newBuilder().build())
                .wrapsRetryCount(1)
                .build();

        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(writableHistoryStore.getLedgerId()).willReturn(ledgerId);
        given(weights.sourceWeightThreshold()).willReturn(1L);
        given(prover.advance(any(), any(), any(), any(), eq(DEFAULT_TSS_CONFIG), any(), eq(true)))
                .willReturn(new HistoryProver.Outcome.Failed(RECOVERABLE_REASON));
        given(weights.sourceNodeIds()).willReturn(new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)));
        given(writableHistoryStore.restartWrapsSigning(
                        CONSTRUCTION_ID, new TreeSet<>(List.of(SELF_ID, OTHER_NODE_ID)), new TreeSet<>(), null))
                .willReturn(restarted);

        setField("targetMetadata", metadata);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, DEFAULT_TSS_CONFIG);
        subject.advanceConstruction(
                Instant.EPOCH.plusSeconds(1), metadata, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        subject.addProofVote(
                OTHER_NODE_ID, vote, Instant.EPOCH.plusSeconds(11), writableHistoryStore, DEFAULT_TSS_CONFIG);

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
        final var proof = recursiveProof("compressed", "uncompressed", expectedHistory)
                .copyBuilder()
                .targetNodeWeights(new ProofNodeWeight(SELF_ID, 10L))
                .build();
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
    void addProofVoteOnlyAcceptsProofOfThisNodesTargetHistory() throws Exception {
        final long lowestNodeId = 0L;
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY);
        // The same compressed proof with another target history
        final var otherHistoryProof = proof.copyBuilder()
                .targetHistory(new History(Bytes.wrap("SOME_OTHER_BOOK_HASH"), METADATA))
                .build();
        final var otherHistoryVote =
                HistoryProofVote.newBuilder().proof(otherHistoryProof).build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        givenTargetHistory();
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightOf(lowestNodeId)).willReturn(10L);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(10L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        // Had the lowest node's proof been valid, its vote alone would have finished the construction
        subject.addProofVote(lowestNodeId, otherHistoryVote, Instant.EPOCH, writableHistoryStore, tssConfig);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(prover)
                .observeProofVote(eq(lowestNodeId), eq(otherHistoryVote), eq(false), eq(ProofVoteCategory.INVALID));
        verify(writableHistoryStore, never()).completeProof(CONSTRUCTION_ID, otherHistoryProof);
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
    }

    @Test
    void addProofVoteOnlyAcceptsProofOfThisNodesTargetProofKeys() throws Exception {
        final long lowestNodeId = 0L;
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        subject.addProofKeyPublication(new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH));
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY)
                .copyBuilder()
                .targetProofKeys(new ProofKey(SELF_ID, PROOF_KEY_1))
                .build();
        // The same compressed proof with another target proof key
        final var otherKeysProof = proof.copyBuilder()
                .targetProofKeys(new ProofKey(SELF_ID, Bytes.wrap("other-key")))
                .build();
        final var otherKeysVote =
                HistoryProofVote.newBuilder().proof(otherKeysProof).build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        givenTargetHistory();
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightOf(lowestNodeId)).willReturn(10L);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(10L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        // Had the lowest node's proof been valid, its vote alone would have finished the construction
        subject.addProofVote(lowestNodeId, otherKeysVote, Instant.EPOCH, writableHistoryStore, tssConfig);
        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(prover).observeProofVote(eq(lowestNodeId), eq(otherKeysVote), eq(false), eq(ProofVoteCategory.INVALID));
        verify(writableHistoryStore, never()).completeProof(CONSTRUCTION_ID, otherKeysProof);
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
    }

    @Test
    void proofValidityIsRecomputedWhenTargetProofKeysChange() throws Exception {
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY)
                .copyBuilder()
                .targetProofKeys(new ProofKey(SELF_ID, PROOF_KEY_1))
                .build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        givenTargetHistory();
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightOf(OTHER_NODE_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(20L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        // The vote is invalid until the key it commits to is published; after that, a node that rebuilds this
        // controller from state counts it as valid, so this controller must not keep its earlier result
        subject.addProofVote(OTHER_NODE_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);
        subject.addProofKeyPublication(new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH));
        subject.addProofVote(
                SELF_ID,
                HistoryProofVote.newBuilder().congruentNodeId(OTHER_NODE_ID).build(),
                Instant.EPOCH,
                writableHistoryStore,
                tssConfig);

        verify(prover).observeProofVote(eq(OTHER_NODE_ID), eq(vote), eq(false), eq(ProofVoteCategory.INVALID));
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
    }

    @Test
    void advanceConstructionStartsAssemblyAfterGracePeriodWhenPublishedWeightMeetsThreshold() {
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 7L, OTHER_NODE_ID, 3L)));
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
        construction = construction
                .copyBuilder()
                .assemblyStartTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
                .build();
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

        given(prover.advance(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(construction);

        subject.advanceConstruction(Instant.EPOCH.plusSeconds(12), METADATA, writableHistoryStore, true, tssConfig);

        verify(prover).replayWrapsSigningMessage(eq(CONSTRUCTION_ID), eq(wrapsMessagePublications.getFirst()));
        verify(writableHistoryStore, never()).setAssemblyTime(anyLong(), any());
    }

    @Test
    void constructorReplaysCongruentVoteChainRegardlessOfMapOrder() throws Exception {
        // Regression for #26524: persisted votes come back from state in HashMap iteration order, NOT the consensus
        // order in which they were cast. A congruent vote replayed before the explicit vote it references must still
        // be counted; otherwise a reconnecting node ends up with less counted weight than the nodes that never
        // restarted and can diverge (ISS) when a later vote completes the proof only on the continuously running
        // nodes. Pin a deliberately hostile order with a LinkedHashMap -- each congruent vote precedes its referent,
        // and the chain node 0 -> node 1 -> node 2 (explicit) is only rebuilt if replay resolves dependencies
        // iteratively rather than in map order -- so the test does not depend on the current JDK's HashMap bucket
        // order.
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY);
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
        givenTargetHistory();
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
    void freshGenesisUsesSelectedCurrentKeysAndDoesNotPassThePredecessorToItsProver() {
        final var oldKey = Bytes.wrap("previous generation key");
        final var previousProof = HistoryProof.newBuilder()
                .targetProofKeys(new ProofKey(SELF_ID, oldKey))
                .build();
        construction = construction
                .copyBuilder()
                .sourceRosterHash(ROSTER_HASH)
                .targetRosterHash(ROSTER_HASH)
                .build();
        keyPublications.add(new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH));
        given(weights.targetIncludes(SELF_ID)).willReturn(true);

        subject = newSubject(previousProof);

        verify(proverFactory)
                .create(
                        eq(SELF_ID),
                        eq(DEFAULT_TSS_CONFIG),
                        eq(keyPair),
                        isNull(),
                        eq(weights),
                        eq(Map.of(SELF_ID, PROOF_KEY_1)),
                        any(),
                        eq(historyLibrary),
                        eq(submissions));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "changed", "omitted", "reordered"})
    void proofVoteRejectsMissingOrForgedCanonicalMembershipBeforeNativeVerification(final String alteration)
            throws Exception {
        final var targetWeights = new TreeMap<>(Map.of(SELF_ID, 10L, OTHER_NODE_ID, 7L));
        given(weights.targetNodeWeights()).willReturn(targetWeights);
        givenTargetHistory();
        final var snapshot = switch (alteration) {
            case "missing" -> List.<ProofNodeWeight>of();
            case "changed" -> List.of(new ProofNodeWeight(SELF_ID, 11L), new ProofNodeWeight(OTHER_NODE_ID, 7L));
            case "omitted" -> List.of(new ProofNodeWeight(SELF_ID, 10L));
            case "reordered" -> List.of(new ProofNodeWeight(OTHER_NODE_ID, 7L), new ProofNodeWeight(SELF_ID, 10L));
            default -> throw new IllegalArgumentException(alteration);
        };
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY)
                .copyBuilder()
                .targetNodeWeights(snapshot)
                .build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(historyLibrary, never()).verifyCompressedProof(any(), any(), any());
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(prover).observeProofVote(SELF_ID, vote, false, ProofVoteCategory.INVALID);
    }

    @Test
    void proofVoteAcceptsTheCompleteCanonicalMembershipIncludingAnUnkeyedNode() throws Exception {
        final var targetWeights = new TreeMap<>(Map.of(SELF_ID, 10L, OTHER_NODE_ID, 7L));
        given(weights.targetNodeWeights()).willReturn(targetWeights);
        givenTargetHistory();
        final var proof = recursiveProof("compressed", "uncompressed", TARGET_HISTORY)
                .copyBuilder()
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(targetWeights))
                .build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(construction);

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        verify(historyLibrary).verifyCompressedProof(any(), any(), any());
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
        verify(prover).observeProofVote(SELF_ID, vote, true, ProofVoteCategory.VALID);
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
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartKeepsExactlyTheKeysSelectedByTheLiveController(final boolean duringSigning) {
        final var cutoff = Instant.EPOCH.plusSeconds(20);
        final var secondKey = Bytes.wrap("second");
        final var accepted = Map.of(SELF_ID, PROOF_KEY_1, OTHER_NODE_ID, secondKey);
        final var first = new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH);
        // This key arrives after the grace deadline, while the live controller still waits for metadata.
        final var atCutoff = new ProofKeyPublication(OTHER_NODE_ID, secondKey, cutoff);
        final var late = new ProofKeyPublication(99L, Bytes.wrap("late"), cutoff.plusNanos(1));
        given(weights.targetIncludes(anyLong())).willReturn(true);
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L, 99L, 1L)));
        given(weights.targetWeightOf(anyLong())).willReturn(1L);
        given(weights.targetWeightThreshold()).willReturn(2L);
        var assembled = construction
                .copyBuilder()
                .assemblyStartTime(asTimestamp(cutoff))
                .proofKeyCutoffTime(asTimestamp(cutoff))
                .build();
        given(writableHistoryStore.setAssemblyTime(CONSTRUCTION_ID, cutoff)).willReturn(assembled);
        subject.addProofKeyPublication(first);
        subject.addProofKeyPublication(atCutoff);
        subject.advanceConstruction(cutoff, METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        subject.addProofKeyPublication(late);
        given(prover.advance(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(assembled);
        subject.advanceConstruction(cutoff.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        verify(prover).advance(any(), any(), eq(METADATA), eq(accepted), any(), any(), eq(true));
        org.mockito.Mockito.clearInvocations(prover);
        if (duringSigning) {
            assembled = assembled
                    .copyBuilder()
                    .wrapsSigningState(WrapsSigningState.newBuilder().phase(R2))
                    .build();
            assertFalse(assembled.hasAssemblyStartTime());
        }
        final var rebuilt = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                assembled,
                weights,
                executor,
                submissions,
                machine,
                List.of(first, atCutoff, late),
                List.of(),
                Map.of(),
                historyService,
                historyLibrary,
                proverFactory,
                null,
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);
        rebuilt.advanceConstruction(cutoff.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        verify(prover).advance(any(), any(), eq(METADATA), eq(accepted), any(), any(), eq(true));
    }

    @Test
    void usesAKeyAdoptedInTheRoundAssemblyStartedLikeAControllerRebuiltFromState() {
        // Before the block signer is ready, assembly starts with the end of its round as cutoff, before that
        // round's transactions are handled; so a key adopted in the round reaches the controller after assembly
        final var cutoff = Instant.EPOCH.plusSeconds(20);
        final var secondKey = Bytes.wrap("second");
        final var first = new ProofKeyPublication(SELF_ID, PROOF_KEY_1, Instant.EPOCH);
        final var sameRound = new ProofKeyPublication(OTHER_NODE_ID, secondKey, cutoff.minusNanos(1));
        final var late = new ProofKeyPublication(99L, Bytes.wrap("late"), cutoff.plusNanos(1));
        given(weights.targetIncludes(anyLong())).willReturn(true);
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L, 99L, 1L)));
        given(weights.targetWeightOf(anyLong())).willReturn(1L);
        given(weights.targetWeightThreshold()).willReturn(1L);
        final var assembled = construction
                .copyBuilder()
                .assemblyStartTime(asTimestamp(cutoff))
                .proofKeyCutoffTime(asTimestamp(cutoff))
                .build();
        given(writableHistoryStore.setAssemblyTime(CONSTRUCTION_ID, cutoff)).willReturn(assembled);
        subject.addProofKeyPublication(first);
        subject.advanceConstruction(cutoff, METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        subject.addProofKeyPublication(sameRound);
        subject.addProofKeyPublication(late);
        given(prover.advance(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .willReturn(HistoryProver.Outcome.InProgress.INSTANCE);
        given(writableHistoryStore.getConstructionOrThrow(CONSTRUCTION_ID)).willReturn(assembled);

        subject.advanceConstruction(cutoff.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        final var rebuilt = new ProofControllerImpl(
                SELF_ID,
                keyPair,
                assembled,
                weights,
                executor,
                submissions,
                machine,
                List.of(first, sameRound, late),
                List.of(),
                Map.of(),
                historyService,
                historyLibrary,
                proverFactory,
                null,
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);
        rebuilt.advanceConstruction(cutoff.plusSeconds(1), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);

        verify(prover, times(2))
                .advance(
                        any(),
                        any(),
                        eq(METADATA),
                        eq(Map.of(SELF_ID, PROOF_KEY_1, OTHER_NODE_ID, secondKey)),
                        any(),
                        any(),
                        eq(true));
    }

    @Test
    void invalidAndCopiedProofKeysAreIgnoredTheSameWayLiveAndRebuiltFromState() throws Exception {
        final var library = new HistoryLibraryImpl();
        final var key1 = Bytes.wrap(library.newSchnorrKeyPair().publicKey());
        final var key4 = Bytes.wrap(library.newSchnorrKeyPair().publicKey());
        final var nodeWeights = new TreeMap<>(Map.of(1L, 1L, 2L, 1L, 3L, 1L, 4L, 1L));
        // Node 2 copies node 4's key after node 4 adopted it, and node 3 publishes bytes that are not a key
        final var publications = List.of(
                new ProofKeyPublication(1L, key1, Instant.EPOCH),
                new ProofKeyPublication(2L, key4, Instant.EPOCH.plusSeconds(2)),
                new ProofKeyPublication(3L, Bytes.wrap(new byte[(int) key1.length()]), Instant.EPOCH.plusSeconds(3)),
                new ProofKeyPublication(4L, key4, Instant.EPOCH.plusSeconds(1)));

        final var live = keySelectingController(library, nodeWeights, List.of());
        publications.stream()
                .sorted(Comparator.comparing(ProofKeyPublication::adoptionTime))
                .forEach(live::addProofKeyPublication);
        final var rebuilt = keySelectingController(library, nodeWeights, publications);

        final var expected = Map.of(1L, key1, 4L, key4);
        assertEquals(expected, targetProofKeysOf(live));
        assertEquals(expected, targetProofKeysOf(rebuilt));
    }

    @Test
    void copiedProofKeySelectionDependsOnAdoptionTimeNotArrivalOrder() throws Exception {
        // A key rotated in when a construction is created is adopted at the last consensus time of that round;
        // so a first key published in the same round can arrive later but have an earlier adoption time
        final var library = new HistoryLibraryImpl();
        final var key = Bytes.wrap(library.newSchnorrKeyPair().publicKey());
        final var nodeWeights = new TreeMap<>(Map.of(1L, 1L, 2L, 1L));
        final var rotated = new ProofKeyPublication(1L, key, Instant.EPOCH.plusSeconds(1));
        final var published =
                new ProofKeyPublication(2L, key, Instant.EPOCH.plusSeconds(1).minusNanos(1));

        final var live = keySelectingController(library, nodeWeights, List.of(rotated));
        live.addProofKeyPublication(published);
        final var rebuilt = keySelectingController(library, nodeWeights, List.of(rotated, published));

        assertEquals(Map.of(2L, key), targetProofKeysOf(live));
        assertEquals(Map.of(2L, key), targetProofKeysOf(rebuilt));
    }

    @Test
    void proofKeyPublishedAfterTheOthersIsCheckedWithOneHash() throws Exception {
        final var invalidKey = Bytes.wrap("invalid");
        given(historyLibrary.hashAddressBook(any())).willAnswer(invocation -> {
            final HistoryLibrary.AddressBook book = invocation.getArgument(0);
            if (Arrays.stream(book.publicKeys()).anyMatch(key -> Arrays.equals(key, invalidKey.toByteArray()))) {
                throw new IllegalArgumentException("Invalid key");
            }
            return TARGET_BOOK_HASH.toByteArray();
        });
        final var nodeWeights = new TreeMap<>(Map.of(1L, 1L, 2L, 1L, 3L, 1L, 4L, 1L, 5L, 1L));
        final var live = keySelectingController(historyLibrary, nodeWeights, List.of());

        live.addProofKeyPublication(new ProofKeyPublication(1L, invalidKey, Instant.EPOCH));
        for (long nodeId = 2L; nodeId <= 5L; nodeId++) {
            live.addProofKeyPublication(
                    new ProofKeyPublication(nodeId, Bytes.wrap("key" + nodeId), Instant.EPOCH.plusSeconds(nodeId)));
        }

        // Keys are not all checked again each time a key arrives after an invalid one
        verify(historyLibrary, times(5)).hashAddressBook(any());
        assertEquals(Set.of(2L, 3L, 4L, 5L), targetProofKeysOf(live).keySet());
    }

    @Test
    void everyProofKeyIsKeptForATargetRosterTooLargeForAnyAddressBook() throws Exception {
        final var nodeWeights = new TreeMap<Long, Long>();
        LongStream.rangeClosed(1, MAX_ADDRESS_BOOK_SIZE + 1).forEach(nodeId -> nodeWeights.put(nodeId, 1L));
        final var publications = List.of(
                new ProofKeyPublication(1L, PROOF_KEY_1, Instant.EPOCH),
                new ProofKeyPublication(2L, PROOF_KEY_1, Instant.EPOCH));

        final var rebuilt = keySelectingController(historyLibrary, nodeWeights, publications);

        // So the prover fails the construction for its address book being too large
        assertEquals(Map.of(1L, PROOF_KEY_1, 2L, PROOF_KEY_1), targetProofKeysOf(rebuilt));
        verify(historyLibrary, never()).hashAddressBook(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidAndCopiedProofKeysDoNotStopWrapsSigning(final boolean replayedFromState) {
        final var library = new HistoryLibraryImpl();
        final var selfKeys = library.newSchnorrKeyPair();
        final var node2Key = Bytes.wrap(library.newSchnorrKeyPair().publicKey());
        // Nodes 4 and 5 hold too little weight to stop assembly once their keys are ignored
        final var nodeWeights = new TreeMap<>(Map.of(SELF_ID, 3L, 2L, 3L, 3L, 3L, 4L, 1L, 5L, 1L));
        final var publications = List.of(
                new ProofKeyPublication(SELF_ID, Bytes.wrap(selfKeys.publicKey()), Instant.EPOCH),
                new ProofKeyPublication(2L, node2Key, Instant.EPOCH),
                new ProofKeyPublication(
                        3L, Bytes.wrap(library.newSchnorrKeyPair().publicKey()), Instant.EPOCH),
                new ProofKeyPublication(4L, Bytes.wrap(new byte[selfKeys.publicKey().length]), Instant.EPOCH),
                new ProofKeyPublication(5L, node2Key, Instant.EPOCH.plusSeconds(1)));
        final HistoryProver.Factory wrapsProverFactory = (s, t, k, p, w, r, x, l, m) -> new WrapsHistoryProver(
                s,
                t.wrapsMessageGracePeriod(),
                k,
                p,
                w,
                r,
                CompletableFuture::delayedExecutor,
                x,
                l,
                m,
                new WrapsMpcStateMachine());
        final var rosterHash = Bytes.wrap("roster");
        final var genesisConstruction = construction
                .copyBuilder()
                .sourceRosterHash(rosterHash)
                .targetRosterHash(rosterHash)
                .build();
        final var assembledConstruction = genesisConstruction
                .copyBuilder()
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();
        // The store holds the construction in state until assembly updates it
        construction = genesisConstruction;
        given(writableHistoryStore.setAssemblyTime(eq(CONSTRUCTION_ID), any())).willAnswer(ignore -> {
            construction = assembledConstruction;
            return construction;
        });
        given(submissions.submitWrapsSigningMessage(eq(R1), any(), eq(CONSTRUCTION_ID)))
                .willReturn(CompletableFuture.completedFuture(null));

        subject = new ProofControllerImpl(
                SELF_ID,
                new ProofKeysAccessorImpl.SchnorrKeyPair(
                        Bytes.wrap(selfKeys.privateKey()), Bytes.wrap(selfKeys.publicKey())),
                genesisConstruction,
                new RosterTransitionWeights(nodeWeights, nodeWeights),
                executor,
                submissions,
                machine,
                replayedFromState ? publications : List.of(),
                wrapsMessagePublications,
                existingVotes,
                historyService,
                library,
                wrapsProverFactory,
                null,
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);
        if (!replayedFromState) {
            publications.forEach(subject::addProofKeyPublication);
        }

        for (int i = 1; i <= 30; i++) {
            subject.advanceConstruction(
                    Instant.EPOCH.plusSeconds(i), METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG);
        }

        verify(submissions).submitWrapsSigningMessage(eq(R1), any(), eq(CONSTRUCTION_ID));
    }

    @Test
    void rejectsWrapsBeforeTargetIsBoundAndAssemblyStarts() {
        assertFalse(subject.addWrapsMessagePublication(
                new WrapsMessagePublication(SELF_ID, Bytes.wrap("r1"), R1, Instant.EPOCH), writableHistoryStore));
        verify(prover, never()).addWrapsSigningMessage(anyLong(), any(), any());
    }

    @Test
    void voteCarriedCertificateCompletesWithoutLocalSharesAndIsPersistedBeforeCallback() {
        final var binding = givenProvenanceSubject();
        final var signature = Bytes.wrap("aggregate");
        final var proof = certifiedProof(signature);
        final var completedBinding = binding.copyBuilder().signature(signature).build();
        final var completed = construction
                .copyBuilder()
                .keyProvenance(completedBinding)
                .targetProof(proof)
                .build();
        given(sourceSigner.verifyAggregate(METADATA, signature)).willReturn(true);
        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightOf(SELF_ID)).willReturn(10L);
        given(weights.sourceWeightThreshold()).willReturn(5L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof)).willReturn(completed);
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        subject.addProofVote(SELF_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);
        subject.addProofVote(OTHER_NODE_ID, vote, Instant.EPOCH, writableHistoryStore, tssConfig);

        final var ordered = inOrder(writableHistoryStore, historyService);
        ordered.verify(writableHistoryStore).setKeyProvenance(CONSTRUCTION_ID, completedBinding);
        ordered.verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
        ordered.verify(historyService).onFinished(eq(writableHistoryStore), eq(completed), any());
        verify(historyService, times(1)).onFinished(any(), any(), any());
        verify(prover, never()).replayWrapsSigningMessage(anyLong(), any());
    }

    @Test
    void missingOrInvalidCertificateCannotCompleteEvenWithEnoughWrappersVotes() {
        givenProvenanceSubject();
        given(weights.sourceWeightThreshold()).willReturn(5L);
        given(weights.sourceWeightOf(anyLong())).willReturn(10L);
        subject.addProofVote(
                SELF_ID,
                HistoryProofVote.newBuilder().proof(certifiedProof(Bytes.EMPTY)).build(),
                Instant.EPOCH,
                writableHistoryStore,
                tssConfig);
        subject.addProofVote(
                OTHER_NODE_ID,
                HistoryProofVote.newBuilder()
                        .proof(certifiedProof(Bytes.wrap("bad")))
                        .build(),
                Instant.EPOCH,
                writableHistoryStore,
                tssConfig);
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(writableHistoryStore, never()).setKeyProvenance(anyLong(), any());
        verify(historyLibrary, never()).verifyCompressedProof(any(), any(), any());
        verify(historyService, never()).onFinished(any(), any(), any());
    }

    @Test
    void certificateBytesAreIncludedInProofValidationCacheIdentity() {
        givenProvenanceSubject();
        final var validSignature = Bytes.wrap("good");
        final var invalidSignature = Bytes.wrap("bad");
        given(sourceSigner.verifyAggregate(METADATA, validSignature)).willReturn(true);
        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightThreshold()).willReturn(3L);
        given(weights.sourceWeightOf(anyLong())).willReturn(2L);
        subject.addProofVote(
                SELF_ID,
                HistoryProofVote.newBuilder()
                        .proof(certifiedProof(validSignature))
                        .build(),
                Instant.EPOCH,
                writableHistoryStore,
                tssConfig);
        subject.addProofVote(
                OTHER_NODE_ID,
                HistoryProofVote.newBuilder()
                        .proof(certifiedProof(invalidSignature))
                        .build(),
                Instant.EPOCH,
                writableHistoryStore,
                tssConfig);
        verify(sourceSigner).verifyAggregate(METADATA, validSignature);
        verify(sourceSigner).verifyAggregate(METADATA, invalidSignature);
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        verify(historyLibrary, times(1)).verifyCompressedProof(any(), any(), any());
    }

    @Test
    void certificateForDifferentTargetCannotCompleteAndBindingCannotChangeInPlace() {
        givenProvenanceSubject();
        given(weights.sourceWeightThreshold()).willReturn(1L);
        final var proof = certifiedProof(Bytes.wrap("aggregate"))
                .copyBuilder()
                .targetHistory(new History(TARGET_BOOK_HASH, Bytes.wrap("different-vk")))
                .build();
        subject.addProofVote(
                SELF_ID,
                HistoryProofVote.newBuilder().proof(proof).build(),
                Instant.EPOCH,
                writableHistoryStore,
                tssConfig);
        verify(sourceSigner, never()).verifyAggregate(any(), any());
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.advanceConstruction(
                        Instant.EPOCH, Bytes.wrap("different-vk"), writableHistoryStore, true, DEFAULT_TSS_CONFIG));
    }

    @Test
    void localProverCompletionCannotBypassCertificateValidation() {
        givenProvenanceSubject();
        final var proof = certifiedProof(Bytes.EMPTY);
        given(prover.advance(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .willReturn(new HistoryProver.Outcome.Completed(proof));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.advanceConstruction(
                        Instant.EPOCH, METADATA, writableHistoryStore, true, DEFAULT_TSS_CONFIG));
        verify(writableHistoryStore, never()).completeProof(anyLong(), any());
    }

    @Test
    void restartRecoversBindingAndCertificateVotesWithoutLocalTranscript() {
        final var signature = Bytes.wrap("aggregate");
        final var proof = certifiedProof(signature);
        existingVotes.put(SELF_ID, HistoryProofVote.newBuilder().proof(proof).build());
        final var binding = givenProvenanceSubject();
        given(sourceSigner.verifyAggregate(METADATA, signature)).willReturn(true);
        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(historyLibrary.verifyCompressedProof(any(), any(), any())).willReturn(true);
        given(weights.sourceWeightThreshold()).willReturn(3L);
        given(weights.sourceWeightOf(anyLong())).willReturn(2L);
        given(writableHistoryStore.completeProof(CONSTRUCTION_ID, proof))
                .willReturn(construction.copyBuilder().targetProof(proof).build());
        subject.addProofVote(
                OTHER_NODE_ID,
                HistoryProofVote.newBuilder().congruentNodeId(SELF_ID).build(),
                Instant.EPOCH,
                writableHistoryStore,
                tssConfig);
        verify(writableHistoryStore)
                .setKeyProvenance(
                        CONSTRUCTION_ID,
                        binding.copyBuilder().signature(signature).build());
        verify(writableHistoryStore).completeProof(CONSTRUCTION_ID, proof);
        verify(historyService).onFinished(any(), any(), any());
    }

    private HintsKeyProvenance givenProvenanceSubject() {
        final var binding = HintsKeyProvenance.newBuilder()
                .sourceHintsConstructionId(7L)
                .targetHintsConstructionId(8L)
                .sourceVerificationKey(Bytes.wrap("source-vk"))
                .targetVerificationKey(METADATA)
                .historyConstructionId(CONSTRUCTION_ID)
                .build();
        given(sourceSigner.constructionId()).willReturn(7L);
        given(sourceSigner.verificationKey()).willReturn(binding.sourceVerificationKey());
        construction = construction
                .copyBuilder()
                .targetMetadata(METADATA)
                .keyProvenance(binding)
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
                DEFAULT_TSS_CONFIG,
                sourceSigner);
        return binding;
    }

    private HistoryProof certifiedProof(final Bytes signature) {
        return recursiveProof("compressed", "uncompressed")
                .copyBuilder()
                .targetHistory(new History(TARGET_BOOK_HASH, METADATA))
                .keyProvenanceSignature(signature)
                .build();
    }

    private void givenGroundingSubject() {
        construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .sourceRosterHash(ROSTER_HASH)
                .targetRosterHash(ROSTER_HASH)
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH.minusSeconds(1)))
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
                .proofKeyCutoffTime(asTimestamp(Instant.EPOCH.plusSeconds(10)))
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

    /**
     * Gives the subject the target metadata, and the library a target address book hash, so that only a proof
     * committing to {@link #TARGET_HISTORY} gets as far as having its compressed proof verified.
     */
    private void givenTargetHistory() throws Exception {
        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        setField("targetMetadata", METADATA);
    }

    /**
     * Returns a controller using the given library and target roster, with the given proof key publications in
     * state; and whose prover is the mock.
     */
    private ProofControllerImpl keySelectingController(
            final HistoryLibrary library,
            final TreeMap<Long, Long> nodeWeights,
            final List<ProofKeyPublication> publications) {
        return new ProofControllerImpl(
                SELF_ID,
                keyPair,
                construction,
                new RosterTransitionWeights(nodeWeights, nodeWeights),
                executor,
                submissions,
                machine,
                publications,
                List.of(),
                Map.of(),
                historyService,
                library,
                (s, t, k, p, w, r, x, l, m) -> prover,
                null,
                historyProofMetrics,
                DEFAULT_TSS_CONFIG);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Bytes> targetProofKeysOf(final ProofControllerImpl controller) throws Exception {
        final var field = ProofControllerImpl.class.getDeclaredField("targetProofKeys");
        field.setAccessible(true);
        return (Map<Long, Bytes>) field.get(controller);
    }

    private void setField(String name, Object value) throws Exception {
        final var field = ProofControllerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(subject, value);
    }
}
