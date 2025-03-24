// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsControllerImplTest {
    private static final int TARGET_ROSTER_SIZE = 16;
    private static final int EXPECTED_PARTY_SIZE = partySizeForRosterNodeCount(TARGET_ROSTER_SIZE);
    private static final long SELF_ID = 42L;
    private static final long CONSTRUCTION_ID = 123L;
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final Instant PREPROCESSING_START_TIME = Instant.ofEpochSecond(1_111_111L, 222);
    private static final AggregationAndVerificationKeys ENCODED_PREPROCESSED_KEYS = new AggregationAndVerificationKeys(
            Bytes.wrap("VK").toByteArray(), Bytes.wrap("AK").toByteArray());
    private static final PreprocessedKeys PREPROCESSED_KEYS = new PreprocessedKeys(Bytes.wrap("AK"), Bytes.wrap("VK"));
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
    private static final Set<Long> SOURCE_NODE_IDS = Set.of(0L, 1L, 2L);
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
        given(weights.targetNodeWeights()).willReturn(TARGET_NODE_WEIGHTS);
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
        final var keys = new PreprocessedKeys(Bytes.wrap("AK"), Bytes.wrap("VK"));
        final var vote = PreprocessingVote.newBuilder().preprocessedKeys(keys).build();

        given(weights.sourceWeightOf(1L)).willReturn(2L);
        given(weights.sourceWeightThreshold()).willReturn(1L);
        given(store.setHintsScheme(CONSTRUCTION_WITH_START_TIME.constructionId(), keys, Map.of()))
                .willReturn(FINISHED_CONSTRUCTION);
        given(store.getActiveConstruction()).willReturn(FINISHED_CONSTRUCTION);

        assertTrue(subject.addPreprocessingVote(1L, vote, store));

        verify(context).setConstruction(FINISHED_CONSTRUCTION);
    }

    @Test
    void setsSchemeAndActiveConstructionGivenVoteAndWinningCongruence() {
        setupWith(CONSTRUCTION_WITH_START_TIME);
        final var keys = new PreprocessedKeys(Bytes.wrap("AK"), Bytes.wrap("VK"));
        final var vote = PreprocessingVote.newBuilder().preprocessedKeys(keys).build();

        given(weights.sourceWeightOf(1L)).willReturn(1L);
        given(weights.sourceWeightThreshold()).willReturn(2L);

        assertTrue(subject.addPreprocessingVote(1L, vote, store));
        assertFalse(subject.addPreprocessingVote(1L, vote, store));

        given(weights.sourceWeightOf(2L)).willReturn(1L);
        given(store.getActiveConstruction()).willReturn(HintsConstruction.DEFAULT);
        final var congruentVote =
                PreprocessingVote.newBuilder().congruentNodeId(1L).build();
        given(store.setHintsScheme(CONSTRUCTION_WITH_START_TIME.constructionId(), keys, Map.of()))
                .willReturn(FINISHED_CONSTRUCTION);
        assertTrue(subject.addPreprocessingVote(2L, congruentVote, store));

        verify(context, never()).setConstruction(any());
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

        verify(store).moveToNextNode(OptionalLong.of(2L), CONSENSUS_NOW.plus(Duration.ofSeconds(10)));
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
                .thenReturn(
                        Map.of(0L, CrsPublicationTransactionBody.DEFAULT, 1L, CrsPublicationTransactionBody.DEFAULT));
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
                store);
    }

    private void runScheduledTasks() {
        Runnable task;
        while ((task = scheduledTasks.poll()) != null) {
            task.run();
        }
    }
}
