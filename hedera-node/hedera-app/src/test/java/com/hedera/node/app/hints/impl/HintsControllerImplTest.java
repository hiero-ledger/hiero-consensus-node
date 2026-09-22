// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.cryptography.hints.AggregationAndVerificationKeys;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.ReadableHintsStore.HintsKeyPublication;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsControllerImplTest {
    private static final byte[] VALID_AGGREGATION_KEY_BYTES = new byte[49];
    private static final int TARGET_ROSTER_SIZE = 16;
    private static final int EXPECTED_PARTY_SIZE = partySizeForRosterNodeCount(TARGET_ROSTER_SIZE);
    private static final long SELF_ID = 42L;
    private static final long CONSTRUCTION_ID = 123L;
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final Instant PREPROCESSING_START_TIME = Instant.ofEpochSecond(1_111_111L, 222);
    private static final AggregationAndVerificationKeys ENCODED_PREPROCESSED_KEYS =
            new AggregationAndVerificationKeys(Bytes.wrap("VK").toByteArray(), VALID_AGGREGATION_KEY_BYTES);
    private static final Bytes AGGREGATION_KEY = Bytes.wrap(VALID_AGGREGATION_KEY_BYTES);
    private static final PreprocessedKeys PREPROCESSED_KEYS = new PreprocessedKeys(AGGREGATION_KEY, Bytes.wrap("VK"));
    private static final TssKeyPair BLS_KEY_PAIR = new TssKeyPair(Bytes.EMPTY, Bytes.EMPTY);
    private static final HintsConstruction UNFINISHED_CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .gracePeriodEndTime(asTimestamp(CONSENSUS_NOW.plusSeconds(1)))
            .build();
    private static final HintsConstruction CONSTRUCTION_WITH_START_TIME = HintsConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .preprocessingStartTime(asTimestamp(PREPROCESSING_START_TIME))
            .build();
    private static final HintsConstruction FINISHED_CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .hintsScheme(HintsScheme.DEFAULT)
            .build();
    private static final HintsKeyPublication EXPECTED_NODE_ONE_PUBLICATION =
            new HintsKeyPublication(1L, Bytes.wrap("ONE"), 1, PREPROCESSING_START_TIME.minusSeconds(1));
    private static final HintsKeyPublication UNEXPECTED_NODE_ONE_PUBLICATION =
            new HintsKeyPublication(1L, Bytes.wrap("ONE"), 15, PREPROCESSING_START_TIME.minusSeconds(1));
    private static final HintsKeyPublication TARDY_NODE_TWO_PUBLICATION =
            new HintsKeyPublication(2L, Bytes.wrap("TWO"), 2, PREPROCESSING_START_TIME.plusSeconds(1));
    private static final SortedMap<Long, Long> TARGET_NODE_WEIGHTS = new TreeMap<>(Map.of(1L, 8L, 2L, 2L));
    private static final SortedMap<Long, Long> SOURCE_NODE_WEIGHTS = new TreeMap<>(Map.of(0L, 8L, 1L, 10L, 2L, 3L));
    private static final SortedSet<Long> SOURCE_NODE_IDS = new TreeSet<>(List.of(0L, 1L, 2L));
    private static final Bytes INITIAL_CRS = Bytes.wrap("CRS");
    private static final Bytes NEW_CRS = Bytes.wrap("newCRS");
    private static final Bytes PROOF = Bytes.wrap("proof");

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsSubmissions submissions;

    @Mock
    private HintsContext context;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private WritableHintsStore store;

    @Mock
    private OnHintsFinished onHintsFinished;

    private final Deque<Runnable> scheduledTasks = new ArrayDeque<>();

    private HintsControllerImpl subject;

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

        subject.advanceConstruction(CONSENSUS_NOW, store, true);

        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void onlyMatchesExpectedNumParties() {
        setupWith(UNFINISHED_CONSTRUCTION);

        assertFalse(subject.hasNumParties(EXPECTED_PARTY_SIZE - 1));
        assertTrue(subject.hasNumParties(EXPECTED_PARTY_SIZE));
    }

    @Test
    void ignoresKeyPublicationIfNotInGracePeriod() {
        setupWith(FINISHED_CONSTRUCTION);

        subject.addHintsKeyPublication(EXPECTED_NODE_ONE_PUBLICATION, INITIAL_CRS);

        verify(weights, never()).targetNodeWeights();
    }

    @Test
    void ignoresKeyPublicationGivenWrongPartyId() {
        setupWithFinalCrs(UNFINISHED_CONSTRUCTION);
        given(weights.targetNodeWeights()).willReturn(TARGET_NODE_WEIGHTS);

        subject.addHintsKeyPublication(UNEXPECTED_NODE_ONE_PUBLICATION, INITIAL_CRS);

        verifyNoMoreInteractions(weights);
    }

    @Test
    void setsNodeIdsAndSchedulesVerificationForExpectedPartyId() {
        setupWith(UNFINISHED_CONSTRUCTION);
        // remove crs publication task
        scheduledTasks.poll();
        given(weights.targetNodeWeights()).willReturn(TARGET_NODE_WEIGHTS);

        subject.addHintsKeyPublication(EXPECTED_NODE_ONE_PUBLICATION, INITIAL_CRS);

        final var task = scheduledTasks.poll();
        assertNotNull(task);
        task.run();
        verify(library)
                .validateHintsKey(
                        INITIAL_CRS,
                        EXPECTED_NODE_ONE_PUBLICATION.hintsKey(),
                        EXPECTED_NODE_ONE_PUBLICATION.partyId(),
                        EXPECTED_PARTY_SIZE);
        assertEquals(OptionalInt.empty(), subject.partyIdOf(1L));
        given(weights.targetIncludes(1L)).willReturn(true);
        assertEquals(OptionalInt.of(1), subject.partyIdOf(1L));
        given(weights.targetIncludes(2L)).willReturn(true);
        assertEquals(OptionalInt.of(2), subject.partyIdOf(2L));
    }

    @Test
    void schedulesPreprocessingWithQualifiedHintsKeysIfProcessingStartTimeIsSetButDoesNotScheduleTwice() {
        setupWith(
                CONSTRUCTION_WITH_START_TIME,
                List.of(EXPECTED_NODE_ONE_PUBLICATION, TARDY_NODE_TWO_PUBLICATION),
                CRSState.newBuilder().stage(CRSStage.COMPLETED).build());
        given(library.validateHintsKey(any(), any(), anyInt(), anyInt())).willReturn(true);
        runScheduledTasks();

        given(library.preprocess(any(), any(), any(), eq(EXPECTED_PARTY_SIZE))).willReturn(ENCODED_PREPROCESSED_KEYS);
        given(submissions.submitHintsVote(CONSTRUCTION_ID, PREPROCESSED_KEYS))
                .willReturn(CompletableFuture.completedFuture(null));
        subject.advanceConstruction(CONSENSUS_NOW, store, true);

        final var task = scheduledTasks.poll();
        assertNotNull(task);
        given(weights.targetWeightOf(1L)).willReturn(TARGET_NODE_WEIGHTS.get(1L));
        task.run();

        verify(submissions).submitHintsVote(CONSTRUCTION_ID, PREPROCESSED_KEYS);

        subject.advanceConstruction(CONSENSUS_NOW, store, true);
        assertTrue(scheduledTasks.isEmpty());

        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void setsPreprocessingStartTimeWhenAllNodesHavePublished() {
        setupWith(UNFINISHED_CONSTRUCTION);
        given(weights.targetNodeWeights()).willReturn(TARGET_NODE_WEIGHTS);
        given(weights.numTargetNodesInSource()).willReturn(2);
        given(store.setPreprocessingStartTime(UNFINISHED_CONSTRUCTION.constructionId(), PREPROCESSING_START_TIME))
                .willReturn(CONSTRUCTION_WITH_START_TIME);

        subject.addHintsKeyPublication(EXPECTED_NODE_ONE_PUBLICATION, INITIAL_CRS);
        subject.addHintsKeyPublication(TARDY_NODE_TWO_PUBLICATION, INITIAL_CRS);
        given(library.validateHintsKey(any(), any(), anyInt(), anyInt())).willReturn(true);
        runScheduledTasks();

        subject.advanceConstruction(PREPROCESSING_START_TIME, store, true);

        // The vote future should have been started
        final var task = requireNonNull(scheduledTasks.poll());
        final Map<Integer, Bytes> expectedHintsKeys =
                Map.of(EXPECTED_NODE_ONE_PUBLICATION.partyId(), EXPECTED_NODE_ONE_PUBLICATION.hintsKey());
        final Map<Integer, Long> expectedWeights = Map.of(EXPECTED_NODE_ONE_PUBLICATION.partyId(), 8L);
        given(library.preprocess(any(), any(), any(), eq(EXPECTED_PARTY_SIZE))).willReturn(ENCODED_PREPROCESSED_KEYS);
        given(submissions.submitHintsVote(CONSTRUCTION_ID, PREPROCESSED_KEYS))
                .willReturn(CompletableFuture.completedFuture(null));
        given(weights.targetWeightOf(1L)).willReturn(TARGET_NODE_WEIGHTS.get(1L));
        task.run();
        verify(submissions).submitHintsVote(FINISHED_CONSTRUCTION.constructionId(), PREPROCESSED_KEYS);
    }

    @Test
    void publishesHintsKeyIfNotDoneBeforeGracePeriodOver() {
        setupWith(UNFINISHED_CONSTRUCTION);
        // remove crs publication task
        scheduledTasks.poll();
        given(weights.numTargetNodesInSource()).willReturn(2);
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 1L)));

        subject.advanceConstruction(PREPROCESSING_START_TIME, store, true);
        assertNull(scheduledTasks.poll());

        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        subject.advanceConstruction(PREPROCESSING_START_TIME, store, true);
        final var task = requireNonNull(scheduledTasks.poll());
        final var hints = Bytes.wrap("HINTS");
        given(library.computeHints(INITIAL_CRS, BLS_KEY_PAIR.privateKey(), 1, EXPECTED_PARTY_SIZE))
                .willReturn(hints);
        given(submissions.submitHintsKey(1, EXPECTED_PARTY_SIZE, hints))
                .willReturn(CompletableFuture.completedFuture(null));
        task.run();
        verify(submissions).submitHintsKey(1, EXPECTED_PARTY_SIZE, hints);

        subject.advanceConstruction(PREPROCESSING_START_TIME, store, true);
        assertNull(scheduledTasks.poll());
    }

    @Test
    void publishesHintsKeyIfNotDoneAfterGracePeriodOverWithoutAdequateWeightFromTarget() {
        setupWith(UNFINISHED_CONSTRUCTION);
        // remove crs publication task
        scheduledTasks.poll();
        given(weights.numTargetNodesInSource()).willReturn(2);
        given(weights.targetNodeWeights()).willReturn(new TreeMap<>(Map.of(SELF_ID, 1L)));
        given(weights.targetWeightThreshold()).willReturn(1L);
        given(weights.targetIncludes(SELF_ID)).willReturn(true);
        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.COMPLETED)
                        .nextContributingNodeId(null)
                        .crs(INITIAL_CRS)
                        .build());

        subject.advanceConstruction(CONSENSUS_NOW.plusSeconds(2), store, true);

        final var task = requireNonNull(scheduledTasks.poll());
        final var hints = Bytes.wrap("HINTS");
        given(library.computeHints(INITIAL_CRS, BLS_KEY_PAIR.privateKey(), 1, EXPECTED_PARTY_SIZE))
                .willReturn(hints);
        given(submissions.submitHintsKey(1, EXPECTED_PARTY_SIZE, hints))
                .willReturn(CompletableFuture.completedFuture(null));
        task.run();
        verify(submissions).submitHintsKey(1, EXPECTED_PARTY_SIZE, hints);

        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void canCancelFutures() {
        setupWith(FINISHED_CONSTRUCTION);

        assertDoesNotThrow(() -> subject.cancelPendingWork());
    }

    @Test
    void addVoteIsNoopWhenComplete() {
        setupWith(FINISHED_CONSTRUCTION);

        assertFalse(subject.addPreprocessingVote(1L, PreprocessingVote.DEFAULT, store));
    }

    @Test
    void setsSchemeAndActiveConstructionGivenWinningVote() {
        setupWith(CONSTRUCTION_WITH_START_TIME);
        final var keys = new PreprocessedKeys(AGGREGATION_KEY, Bytes.wrap("VK"));
        final var vote = PreprocessingVote.newBuilder().preprocessedKeys(keys).build();

        given(weights.sourceWeightOf(1L)).willReturn(2L);
        given(weights.sourceWeightThreshold()).willReturn(1L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(), keys, Map.of(), weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        assertTrue(subject.addPreprocessingVote(1L, vote, store));

        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void setsSchemeAndBothConstructionsGivenVoteAndWinningCongruenceWithActiveId() {
        setupWith(CONSTRUCTION_WITH_START_TIME);
        final var keys = new PreprocessedKeys(AGGREGATION_KEY, Bytes.wrap("VK"));
        final var vote = PreprocessingVote.newBuilder().preprocessedKeys(keys).build();

        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(2L);

        assertTrue(subject.addPreprocessingVote(1L, vote, store));
        assertFalse(subject.addPreprocessingVote(1L, vote, store));

        given(weights.sourceWeightOf(2L)).willReturn(1L);
        final var congruentVote =
                PreprocessingVote.newBuilder().congruentNodeId(1L).build();
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(), keys, Map.of(), weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);
        assertTrue(subject.addPreprocessingVote(2L, congruentVote, store));

        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void crsPublicationsInConstructorWhenNotValid() {
        setupWith(UNFINISHED_CONSTRUCTION);

        verify(library, never()).verifyCrsUpdate(eq(INITIAL_CRS), any(), any());
    }

    @Test
    void setsCRSPublicationsInConstructorWhenValid() {
        setupWith(
                UNFINISHED_CONSTRUCTION,
                List.of(),
                CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .crs(INITIAL_CRS)
                        .build());
        lenient()
                .when(store.getCrsPublications())
                .thenReturn(List.of(CrsPublicationTransactionBody.newBuilder().build()));
        given(library.verifyCrsUpdate(any(), any(), any())).willReturn(true);
        final var task = requireNonNull(scheduledTasks.poll());
        task.run();

        verify(library).verifyCrsUpdate(eq(INITIAL_CRS), any(), any());
    }

    @Test
    void addsCRSPublications() {
        setupWith(UNFINISHED_CONSTRUCTION);
        given(library.verifyCrsUpdate(any(), any(), any())).willReturn(true);

        subject.addCrsPublication(
                CrsPublicationTransactionBody.newBuilder()
                        .newCrs(NEW_CRS)
                        .proof(PROOF)
                        .build(),
                CONSENSUS_NOW,
                store,
                0L);

        final var task1 = requireNonNull(scheduledTasks.poll());
        task1.run();
        verify(library).verifyCrsUpdate(any(), eq(NEW_CRS), eq(PROOF));
    }

    @Test
    void setsFinalCRSIfAllIdsCompleted() {
        setupWith(UNFINISHED_CONSTRUCTION);

        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(null)
                        .crs(INITIAL_CRS)
                        .build());
        subject.advanceCrsWork(CONSENSUS_NOW, store, true);

        verify(store)
                .setCrsState(CRSState.newBuilder()
                        .stage(CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS)
                        .nextContributingNodeId(null)
                        .contributionEndTime(asTimestamp(CONSENSUS_NOW.plus(Duration.ofSeconds(5))))
                        .crs(INITIAL_CRS)
                        .build());
    }

    @Test
    void setsFinalCRSAndRemovesContributionEndTime() {
        setupWith(UNFINISHED_CONSTRUCTION);

        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS)
                        .nextContributingNodeId(null)
                        .contributionEndTime(asTimestamp(CONSENSUS_NOW.minus(Duration.ofSeconds(7))))
                        .crs(INITIAL_CRS)
                        .build());
        given(weights.sourceNodeWeights()).willReturn(SOURCE_NODE_WEIGHTS);
        subject.setFinalCrsFuture(
                CompletableFuture.completedFuture(new HintsControllerImpl.CRSValidation(INITIAL_CRS, 18)));
        subject.advanceCrsWork(CONSENSUS_NOW, store, true);

        verify(store)
                .setCrsState(CRSState.newBuilder()
                        .crs(INITIAL_CRS)
                        .stage(CRSStage.COMPLETED)
                        .nextContributingNodeId(null)
                        .contributionEndTime((Timestamp) null)
                        .build());
    }

    @Test
    void repeatProcessIfThresholdNotMet() {
        setupWith(UNFINISHED_CONSTRUCTION);

        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS)
                        .nextContributingNodeId(null)
                        .contributionEndTime(asTimestamp(CONSENSUS_NOW.minus(Duration.ofSeconds(7))))
                        .crs(INITIAL_CRS)
                        .build());
        given(weights.sourceNodeWeights()).willReturn(SOURCE_NODE_WEIGHTS);
        subject.setFinalCrsFuture(
                CompletableFuture.completedFuture(new HintsControllerImpl.CRSValidation(INITIAL_CRS, 1)));
        subject.advanceCrsWork(CONSENSUS_NOW, store, true);

        verify(store, never())
                .setCrsState(CRSState.newBuilder()
                        .stage(CRSStage.COMPLETED)
                        .nextContributingNodeId(null)
                        .contributionEndTime((Timestamp) null)
                        .crs(INITIAL_CRS)
                        .build());
        verify(store)
                .setCrsState(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(0L)
                        .contributionEndTime(asTimestamp(CONSENSUS_NOW.plus(Duration.ofSeconds(10))))
                        .crs(INITIAL_CRS)
                        .build());
    }

    @Test
    void movesToNextNodeIfTimeLimitExceeded() {
        setupWith(UNFINISHED_CONSTRUCTION);

        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(1L)
                        .contributionEndTime(asTimestamp(CONSENSUS_NOW.minus(Duration.ofSeconds(7))))
                        .crs(INITIAL_CRS)
                        .build());

        given(weights.sourceNodeIds()).willReturn(SOURCE_NODE_IDS);
        subject.setFinalCrsFuture(
                CompletableFuture.completedFuture(new HintsControllerImpl.CRSValidation(INITIAL_CRS, 1)));
        subject.advanceCrsWork(CONSENSUS_NOW, store, true);

        verify(store).moveToNextNode(2L, CONSENSUS_NOW.plus(Duration.ofSeconds(10)));
    }

    @Test
    void submitsCRSUpdateIfSelf() {
        setupWith(UNFINISHED_CONSTRUCTION);

        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(SELF_ID)
                        .contributionEndTime(asTimestamp(CONSENSUS_NOW.plus(Duration.ofSeconds(7))))
                        .crs(INITIAL_CRS)
                        .build());
        given(library.updateCrs(any(), any())).willReturn(NEW_CRS);
        given(submissions.submitCrsUpdate(any(), any())).willReturn(CompletableFuture.completedFuture(null));
        assertTrue(scheduledTasks.isEmpty());

        subject.advanceCrsWork(CONSENSUS_NOW, store, true);

        final var task1 = requireNonNull(scheduledTasks.poll());
        task1.run();

        verify(library).updateCrs(eq(INITIAL_CRS), any());
        verify(submissions).submitCrsUpdate(any(), any());
    }

    // -----------------------------------------------------------------------
    // Regression tests for congruent-vote replay (issue #26527)
    // -----------------------------------------------------------------------

    @Test
    void reconstructsFromExplicitAndCongruentVotesWithoutException() {
        // Reproduces the restart failure: node 2 explicit, node 0 congruent -> node 2.
        // Without the fix, addPreprocessingVote throws NPE when tallying the in-memory votes.
        final var explicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        final var congruentVote =
                PreprocessingVote.newBuilder().congruentNodeId(2L).build();
        setupWithVotes(CONSTRUCTION_WITH_START_TIME, Map.of(2L, explicitVote, 0L, congruentVote));

        given(weights.sourceWeightOf(0L)).willReturn(8L);
        given(weights.sourceWeightOf(2L)).willReturn(8L);
        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(100L);

        assertTrue(assertDoesNotThrow(() -> subject.addPreprocessingVote(1L, explicitVote, store)));
    }

    @Test
    void reconstructsCongruentVoteWhenExplicitStoredAfterCongruent() {
        // LinkedHashMap forces congruent-first iteration; a naive single-pass loop would fail.
        final var votesMap = new LinkedHashMap<Long, PreprocessingVote>();
        votesMap.put(0L, PreprocessingVote.newBuilder().congruentNodeId(2L).build()); // congruent first
        votesMap.put(
                2L,
                PreprocessingVote.newBuilder()
                        .preprocessedKeys(PREPROCESSED_KEYS)
                        .build()); // explicit second

        setupWithVotes(CONSTRUCTION_WITH_START_TIME, votesMap);

        given(weights.sourceWeightOf(0L)).willReturn(8L);
        given(weights.sourceWeightOf(2L)).willReturn(8L);
        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(100L);

        final var newVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertDoesNotThrow(() -> subject.addPreprocessingVote(1L, newVote, store));
    }

    @Test
    void reconstructsMultiHopCongruentChain() {
        // node 0 -> node 1 -> node 2 -> explicit keys (two hops)
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(
                        2L,
                                PreprocessingVote.newBuilder()
                                        .preprocessedKeys(PREPROCESSED_KEYS)
                                        .build(),
                        1L, PreprocessingVote.newBuilder().congruentNodeId(2L).build(),
                        0L, PreprocessingVote.newBuilder().congruentNodeId(1L).build()));

        // Nodes 0, 1, 2 each contribute weight 4 (total 12); threshold 11.
        // Node 99's vote triggers tally evaluation after replay.
        // If any chain member were dropped, the total would be at most 9 < 11 and the scheme would not complete.
        given(weights.sourceWeightOf(0L)).willReturn(4L);
        given(weights.sourceWeightOf(1L)).willReturn(4L);
        given(weights.sourceWeightOf(2L)).willReturn(4L);
        given(weights.sourceWeightOf(99L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(11L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        final var newVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertTrue(subject.addPreprocessingVote(99L, newVote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void handlesCongruentVoteWithMissingReferent() {
        // Node 0 refers to node 99, which has no vote in the store. Node 0 remains pending.
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(0L, PreprocessingVote.newBuilder().congruentNodeId(99L).build()));

        // Node 0 is absent from the effective tally, so sourceWeightOf(0L) is never consulted.
        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(100L);

        final var vote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertDoesNotThrow(() -> subject.addPreprocessingVote(1L, vote, store));
    }

    @Test
    void handlesCongruentVoteWithCyclicReference() {
        // Node 0 -> node 1 -> node 0 is a cycle. Both remain pending.
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(
                        0L, PreprocessingVote.newBuilder().congruentNodeId(1L).build(),
                        1L, PreprocessingVote.newBuilder().congruentNodeId(0L).build()));

        // Nodes 0 and 1 are absent from the effective tally, so sourceWeightOf is only called for node 2.
        given(weights.sourceWeightOf(2L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(100L);

        final var vote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertDoesNotThrow(() -> subject.addPreprocessingVote(2L, vote, store));
    }

    @Test
    void handlesDefaultOrEmptyVote() {
        // PreprocessingVote.DEFAULT has neither preprocessedKeys nor congruentNodeId and cannot contribute.
        setupWithVotes(CONSTRUCTION_WITH_START_TIME, Map.of(0L, PreprocessingVote.DEFAULT));

        // Node 0 is absent from the effective tally, so only node 1's weight is consulted.
        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(100L);

        final var vote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertDoesNotThrow(() -> subject.addPreprocessingVote(1L, vote, store));
    }

    @Test
    void rejectsDuplicateVoteAfterReplay() {
        // Nodes that were replayed on restart must not be counted twice.
        final var explicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(
                        2L,
                        explicitVote,
                        0L,
                        PreprocessingVote.newBuilder().congruentNodeId(2L).build()));

        assertFalse(subject.addPreprocessingVote(2L, explicitVote, store));
        assertFalse(subject.addPreprocessingVote(
                0L, PreprocessingVote.newBuilder().congruentNodeId(2L).build(), store));
    }

    @Test
    void reconstructsCongruentVoteWithReferentOutsideSourceNodeSet() {
        // Node 2 is NOT in the initial rawVotes map (simulating a non-source-roster node whose vote
        // must be fetched on demand). Node 0 (source node) casts a congruent vote pointing to node 2.
        final var node2ExplicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        final var node0CongruentVote =
                PreprocessingVote.newBuilder().congruentNodeId(2L).build();

        given(weights.targetRosterSize()).willReturn(TARGET_ROSTER_SIZE);
        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.COMPLETED)
                        .crs(INITIAL_CRS)
                        .build());
        // General fallback: any on-demand lookup returns empty
        lenient().when(store.getVotes(anyLong(), any())).thenReturn(Map.of());
        // Specific override: node 2 is found when fetched on demand
        given(store.getVotes(CONSTRUCTION_ID, Set.of(2L))).willReturn(Map.of(2L, node2ExplicitVote));

        subject = new HintsControllerImpl(
                SELF_ID,
                BLS_KEY_PAIR.privateKey(),
                CONSTRUCTION_WITH_START_TIME,
                weights,
                scheduledTasks::offer,
                library,
                Map.of(0L, node0CongruentVote),
                List.of(),
                submissions,
                context,
                HederaTestConfigBuilder::createConfig,
                store,
                onHintsFinished);

        // Node 0 was resolved via on-demand store lookup; its weight of 100 alone crosses the threshold of 50.
        // Node 2 is also retained in votes under its own ID; sourceWeightOf(2L) returns 0 by default.
        given(weights.sourceWeightOf(0L)).willReturn(100L);
        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(50L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        final var vote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertTrue(subject.addPreprocessingVote(1L, vote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void completesConstructionAfterReplay() {
        // Threshold crossing must work correctly after congruent votes are resolved from state.
        final var explicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(
                        2L,
                        explicitVote,
                        0L,
                        PreprocessingVote.newBuilder().congruentNodeId(2L).build()));

        given(weights.sourceWeightOf(0L)).willReturn(8L);
        given(weights.sourceWeightOf(2L)).willReturn(8L);
        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(15L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        assertTrue(subject.addPreprocessingVote(1L, explicitVote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void asyncVoteSelectionAfterReplayDoesNotThrow() {
        // After replay with congruent votes, the async preprocessing future must be able to select
        // a congruent node without calling preprocessedKeysOrThrow() on an unresolved entry.
        final var explicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(
                        2L,
                        explicitVote,
                        0L,
                        PreprocessingVote.newBuilder().congruentNodeId(2L).build()),
                List.of(EXPECTED_NODE_ONE_PUBLICATION),
                CRSState.newBuilder().stage(CRSStage.COMPLETED).crs(INITIAL_CRS).build());

        given(library.validateHintsKey(any(), any(), anyInt(), anyInt())).willReturn(true);
        runScheduledTasks();

        given(library.preprocess(any(), any(), any(), eq(EXPECTED_PARTY_SIZE))).willReturn(ENCODED_PREPROCESSED_KEYS);
        // The future will vote congruently; iteration order determines which node (0 or 2) is picked.
        lenient()
                .when(submissions.submitHintsVote(CONSTRUCTION_ID, 0L))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient()
                .when(submissions.submitHintsVote(CONSTRUCTION_ID, 2L))
                .thenReturn(CompletableFuture.completedFuture(null));
        given(weights.targetWeightOf(1L)).willReturn(TARGET_NODE_WEIGHTS.get(1L));

        subject.advanceConstruction(CONSENSUS_NOW, store, true);

        final var task = requireNonNull(scheduledTasks.poll());
        assertDoesNotThrow(task::run);
        // Verify the future actually submitted a vote (catches async exceptions swallowed by the catch block)
        verify(submissions).submitHintsVote(eq(CONSTRUCTION_ID), anyLong());
    }

    @Test
    void pendingCongruentVoteResolvedWhenReferentVotesLater() {
        // Node 0 congruent -> node 2, but node 2 has not yet voted at construction time.
        // When node 2 later casts an explicit vote, node 0's pending entry must be retroactively resolved.
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(0L, PreprocessingVote.newBuilder().congruentNodeId(2L).build()));

        // node 0 (8) + node 2 (8) = 16 >= threshold 15 once both are counted.
        given(weights.sourceWeightOf(0L)).willReturn(8L);
        given(weights.sourceWeightOf(2L)).willReturn(8L);
        given(weights.sourceWeightThreshold()).willReturn(15L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        final var explicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertTrue(subject.addPreprocessingVote(2L, explicitVote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void pendingCongruentVoteChainResolvesTransitively() {
        // Nodes 0 -> 1 -> 2 are all persisted before node 2's explicit vote arrives live.
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(
                        0L, PreprocessingVote.newBuilder().congruentNodeId(1L).build(),
                        1L, PreprocessingVote.newBuilder().congruentNodeId(2L).build()));

        given(weights.sourceWeightOf(0L)).willReturn(4L);
        given(weights.sourceWeightOf(1L)).willReturn(4L);
        given(weights.sourceWeightOf(2L)).willReturn(4L);
        given(weights.sourceWeightThreshold()).willReturn(11L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        final var explicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();
        assertTrue(subject.addPreprocessingVote(2L, explicitVote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void pendingDependentsResolveWhenReferentResolvesCongruently() {
        // Node 0 waits on node 1, while node 2's explicit vote is already persisted. When node 1
        // votes congruently with node 2, resolving node 1 must also resolve node 0.
        setupWithVotes(
                CONSTRUCTION_WITH_START_TIME,
                Map.of(
                        0L, PreprocessingVote.newBuilder().congruentNodeId(1L).build(),
                        2L,
                                PreprocessingVote.newBuilder()
                                        .preprocessedKeys(PREPROCESSED_KEYS)
                                        .build()));

        given(weights.sourceWeightOf(0L)).willReturn(4L);
        given(weights.sourceWeightOf(1L)).willReturn(4L);
        given(weights.sourceWeightOf(2L)).willReturn(4L);
        given(weights.sourceWeightThreshold()).willReturn(11L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        final var congruentVote =
                PreprocessingVote.newBuilder().congruentNodeId(2L).build();
        assertTrue(subject.addPreprocessingVote(1L, congruentVote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void loadsAndResolvesCompleteOnDemandReferentChain() {
        // Only node 0 is supplied initially; nodes 1 and 2 must be loaded recursively as
        // node 0 -> node 1 -> node 2 -> explicit keys.
        final var nodeOneVote =
                PreprocessingVote.newBuilder().congruentNodeId(2L).build();
        final var nodeTwoVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();

        given(weights.targetRosterSize()).willReturn(TARGET_ROSTER_SIZE);
        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.COMPLETED)
                        .crs(INITIAL_CRS)
                        .build());
        lenient().when(store.getVotes(anyLong(), any())).thenReturn(Map.of());
        given(store.getVotes(CONSTRUCTION_ID, Set.of(1L))).willReturn(Map.of(1L, nodeOneVote));
        given(store.getVotes(CONSTRUCTION_ID, Set.of(2L))).willReturn(Map.of(2L, nodeTwoVote));

        subject = new HintsControllerImpl(
                SELF_ID,
                BLS_KEY_PAIR.privateKey(),
                CONSTRUCTION_WITH_START_TIME,
                weights,
                scheduledTasks::offer,
                library,
                Map.of(0L, PreprocessingVote.newBuilder().congruentNodeId(1L).build()),
                List.of(),
                submissions,
                context,
                HederaTestConfigBuilder::createConfig,
                store,
                onHintsFinished);

        // Nodes 1 and 2 carry no source weight. A live vote from node 3 that refers to the
        // on-demand node 1 should still resolve, giving nodes 0 and 3 enough weight to finish.
        given(weights.sourceWeightOf(0L)).willReturn(4L);
        given(weights.sourceWeightOf(1L)).willReturn(0L);
        given(weights.sourceWeightOf(2L)).willReturn(0L);
        given(weights.sourceWeightOf(3L)).willReturn(4L);
        given(weights.sourceWeightThreshold()).willReturn(8L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        final var congruentVote =
                PreprocessingVote.newBuilder().congruentNodeId(1L).build();
        assertTrue(subject.addPreprocessingVote(3L, congruentVote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    @Test
    void onDemandReferentRetainedForSubsequentCongruentVotes() {
        // Node 2 is outside sourceNodeIds and fetched on-demand during construction for node 0's congruent vote.
        // Node 2 is cached under its own ID in votes, so a subsequent live congruent vote from
        // node 1 pointing to node 2 can resolve without another store lookup.
        final var node2ExplicitVote = PreprocessingVote.newBuilder()
                .preprocessedKeys(PREPROCESSED_KEYS)
                .build();

        given(weights.targetRosterSize()).willReturn(TARGET_ROSTER_SIZE);
        given(store.getCrsState())
                .willReturn(CRSState.newBuilder()
                        .stage(CRSStage.COMPLETED)
                        .crs(INITIAL_CRS)
                        .build());
        lenient().when(store.getVotes(anyLong(), any())).thenReturn(Map.of());
        given(store.getVotes(CONSTRUCTION_ID, Set.of(2L))).willReturn(Map.of(2L, node2ExplicitVote));

        subject = new HintsControllerImpl(
                SELF_ID,
                BLS_KEY_PAIR.privateKey(),
                CONSTRUCTION_WITH_START_TIME,
                weights,
                scheduledTasks::offer,
                library,
                Map.of(0L, PreprocessingVote.newBuilder().congruentNodeId(2L).build()),
                List.of(),
                submissions,
                context,
                HederaTestConfigBuilder::createConfig,
                store,
                onHintsFinished);

        // Node 2 is a zero-weight referent. Nodes 0 and 1 still total 8 after node 1's live
        // congruent vote resolves through the cached node 2.
        given(weights.sourceWeightOf(0L)).willReturn(4L);
        given(weights.sourceWeightOf(1L)).willReturn(4L);
        given(weights.sourceWeightThreshold()).willReturn(8L);
        given(store.setHintsScheme(
                        CONSTRUCTION_WITH_START_TIME.constructionId(),
                        PREPROCESSED_KEYS,
                        Map.of(),
                        weights.targetNodeWeights()))
                .willReturn(FINISHED_CONSTRUCTION);

        final var congruentVote =
                PreprocessingVote.newBuilder().congruentNodeId(2L).build();
        assertTrue(subject.addPreprocessingVote(1L, congruentVote, store));
        verify(onHintsFinished).accept(any(), any(), eq(context));
    }

    private void setupWithVotes(
            @NonNull final HintsConstruction construction, @NonNull final Map<Long, PreprocessingVote> votes) {
        setupWithVotes(
                construction,
                votes,
                List.of(),
                CRSState.newBuilder().stage(CRSStage.COMPLETED).crs(INITIAL_CRS).build());
    }

    private void setupWithVotes(
            @NonNull final HintsConstruction construction,
            @NonNull final Map<Long, PreprocessingVote> votes,
            @NonNull final List<HintsKeyPublication> publications,
            @NonNull final CRSState crsState) {
        given(weights.targetRosterSize()).willReturn(TARGET_ROSTER_SIZE);
        lenient().when(store.getCrsState()).thenReturn(crsState);
        // On-demand store lookups for missing referents return empty by default; specific tests override this.
        lenient().when(store.getVotes(anyLong(), any())).thenReturn(Map.of());
        // Used when updateHintsKey logs target weight info for each publication.
        lenient().when(weights.targetWeightOf(anyLong())).thenReturn(1L);
        lenient().when(weights.targetWeightThreshold()).thenReturn(Long.MAX_VALUE);
        subject = new HintsControllerImpl(
                SELF_ID,
                BLS_KEY_PAIR.privateKey(),
                construction,
                weights,
                scheduledTasks::offer,
                library,
                votes,
                publications,
                submissions,
                context,
                HederaTestConfigBuilder::createConfig,
                store,
                onHintsFinished);
    }

    private void setupWith(@NonNull final HintsConstruction construction) {
        setupWith(
                construction,
                List.of(),
                CRSState.newBuilder().stage(CRSStage.COMPLETED).crs(INITIAL_CRS).build());
    }

    private void setupWithFinalCrs(@NonNull final HintsConstruction construction) {
        setupWith(
                construction,
                List.of(),
                CRSState.newBuilder().stage(CRSStage.COMPLETED).crs(INITIAL_CRS).build());
    }

    private void setupWith(
            @NonNull final HintsConstruction construction,
            @NonNull final List<HintsKeyPublication> publications,
            @NonNull CRSState crsState) {
        given(weights.targetRosterSize()).willReturn(TARGET_ROSTER_SIZE);
        lenient().when(store.getCrsState()).thenReturn(crsState);
        lenient()
                .when(store.getCrsPublications())
                .thenReturn(List.of(CrsPublicationTransactionBody.newBuilder().build()));
        lenient()
                .when(store.getOrderedCrsPublications(any()))
                .thenReturn(new TreeMap<>(
                        Map.of(0L, CrsPublicationTransactionBody.DEFAULT, 1L, CrsPublicationTransactionBody.DEFAULT)));
        subject = new HintsControllerImpl(
                SELF_ID,
                BLS_KEY_PAIR.privateKey(),
                construction,
                weights,
                scheduledTasks::offer,
                library,
                Map.of(),
                publications,
                submissions,
                context,
                HederaTestConfigBuilder::createConfig,
                store,
                onHintsFinished);
    }

    private void runScheduledTasks() {
        Runnable task;
        while ((task = scheduledTasks.poll()) != null) {
            task.run();
        }
    }
}
