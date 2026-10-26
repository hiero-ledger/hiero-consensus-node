// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.node.state.hints.CRSStage.GATHERING_CONTRIBUTIONS;
import static com.hedera.hapi.node.state.hints.CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.CrsContributor;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class CrsCeremonyTest {
    private static final Instant NOW = Instant.ofEpochSecond(12345);
    private static final Bytes SEED = Bytes.wrap("seed");
    private static final Bytes NEXT = Bytes.wrap("next");
    private static final Bytes PROOF = Bytes.wrap("proof");
    private static final HintsConstruction CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(11)
            .crsId(9)
            .numParties(8)
            .build();
    private final HintsLibrary library = mock(HintsLibrary.class);
    private final WritableHintsStore store = mock(WritableHintsStore.class);
    private final HintsSubmissions submissions = mock(HintsSubmissions.class);
    private final AtomicReference<CRSState> state = new AtomicReference<>();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private HintsControllerImpl subject;

    @BeforeEach
    void setup() {
        state.set(CRSState.newBuilder()
                .ceremonyId(9)
                .constructionId(11)
                .numParties(8)
                .attempt(1)
                .crs(SEED)
                .initialCrs(SEED)
                .stage(GATHERING_CONTRIBUTIONS)
                .contributors(List.of(new CrsContributor(42, 2), new CrsContributor(99, 1)))
                .nextContributingNodeId(42L)
                .contributionEndTime(asTimestamp(NOW.plusSeconds(10)))
                .build());
        when(store.getCrsStateFor(CONSTRUCTION)).thenAnswer(inv -> state.get());
        doAnswer(inv -> {
                    state.set(inv.getArgument(1));
                    return null;
                })
                .when(store)
                .setCrsStateFor(eq(CONSTRUCTION), any());
        subject = controller();
    }

    private HintsControllerImpl controller() {
        return new HintsControllerImpl(
                42,
                Bytes.EMPTY,
                CONSTRUCTION,
                mock(RosterTransitionWeights.class),
                tasks::add,
                library,
                Map.of(),
                List.of(),
                submissions,
                mock(HintsContext.class),
                HederaTestConfigBuilder::createConfig,
                store,
                mock(OnHintsFinished.class));
    }

    private CrsPublicationTransactionBody publication() {
        return CrsPublicationTransactionBody.newBuilder()
                .ceremonyId(9)
                .attempt(1)
                .previousCrsHash(noThrowSha384HashOf(SEED))
                .newCrs(NEXT)
                .proof(PROOF)
                .build();
    }

    @Test
    void acceptsValidatedHeadAndPersistsUniqueContributorWithoutTouchingActiveCrs() {
        when(library.verifyCrsUpdate(SEED, NEXT, PROOF)).thenReturn(true);
        subject.addCrsPublication(publication(), NOW, store, 42);
        assertEquals(NEXT, state.get().crs());
        assertEquals(List.of(42L), state.get().contributedNodeIds());
        assertEquals(99L, state.get().nextContributingNodeIdOrThrow());
        verify(store, never()).setCrsState(any());
        final var after = state.get();
        subject.addCrsPublication(publication(), NOW, store, 42);
        assertEquals(after, state.get());
        verify(library, times(1)).verifyCrsUpdate(any(), any(), any());
    }

    @Test
    void staleOrInvalidContributionsDoNotConsumeTheContributorsTurn() {
        final var before = state.get();
        subject.addCrsPublication(publication().copyBuilder().ceremonyId(8).build(), NOW, store, 42);
        subject.addCrsPublication(publication().copyBuilder().attempt(0).build(), NOW, store, 42);
        subject.addCrsPublication(
                publication().copyBuilder().previousCrsHash(Bytes.EMPTY).build(), NOW, store, 42);
        subject.addCrsPublication(publication(), NOW, store, 99);
        subject.addCrsPublication(publication(), NOW.plusSeconds(10), store, 42);
        verifyNoInteractions(library);
        subject.addCrsPublication(publication(), NOW, store, 42);
        assertEquals(before, state.get());
        verify(library).verifyCrsUpdate(SEED, NEXT, PROOF);
    }

    @Test
    void rejectsPreprocessingVotesBeforeCeremonyAndKeyCollectionFinish() {
        assertFalse(
                subject.addPreprocessingVote(42, com.hedera.hapi.node.state.hints.PreprocessingVote.DEFAULT, store));
        state.set(state.get().copyBuilder().stage(COMPLETED).build());
        assertFalse(
                subject.addPreprocessingVote(42, com.hedera.hapi.node.state.hints.PreprocessingVote.DEFAULT, store));
        verify(store, never()).addPreprocessingVote(anyLong(), anyLong(), any());
        verify(store, never()).setHintsScheme(anyLong(), any(), any(), any());
    }

    @Test
    void retryResetsTranscriptAndDistinctWeightAndChangesAttempt() {
        state.set(state.get()
                .copyBuilder()
                .stage(WAITING_FOR_ADOPTING_FINAL_CRS)
                .nextContributingNodeId((Long) null)
                .contributionEndTime(asTimestamp(NOW))
                .crs(NEXT)
                .contributedNodeIds(List.of(42L, 42L))
                .build());
        subject.advanceCrsWork(NOW, store, false);
        assertEquals(GATHERING_CONTRIBUTIONS, state.get().stage());
        assertEquals(SEED, state.get().crs());
        assertEquals(2, state.get().attempt());
        assertTrue(state.get().contributedNodeIds().isEmpty());
        assertEquals(42L, state.get().nextContributingNodeIdOrThrow());
        subject.addCrsPublication(publication(), NOW, store, 42);
        verifyNoInteractions(library);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completesOnlyAfterDistinctThresholdAndFinalizationDeadline(boolean isActive) {
        state.set(state.get()
                .copyBuilder()
                .stage(WAITING_FOR_ADOPTING_FINAL_CRS)
                .nextContributingNodeId((Long) null)
                .contributionEndTime(asTimestamp(NOW.plusSeconds(1)))
                .crs(NEXT)
                .contributedNodeIds(List.of(42L, 99L))
                .build());
        subject.advanceCrsWork(NOW, store, isActive);
        assertEquals(WAITING_FOR_ADOPTING_FINAL_CRS, state.get().stage());
        subject.advanceCrsWork(NOW.plusSeconds(1), store, isActive);
        assertEquals(COMPLETED, state.get().stage());
        assertEquals(NEXT, state.get().crs());
        assertFalse(state.get().hasContributionEndTime());
    }

    @ParameterizedTest
    @EnumSource(CRSStage.class)
    void restartReplaysEveryStageFromDurableState(CRSStage stage) {
        final var before = state.get()
                .copyBuilder()
                .stage(stage)
                .nextContributingNodeId(stage == GATHERING_CONTRIBUTIONS ? 42L : null)
                .contributionEndTime(asTimestamp(NOW))
                .contributedNodeIds(List.of(42L, 99L))
                .build();
        state.set(before);
        subject.advanceCrsWork(NOW, store, false);
        final var uninterrupted = state.get();
        state.set(before);
        controller().advanceCrsWork(NOW, store, false);
        assertEquals(uninterrupted, state.get());
        verifyNoInteractions(library);
    }

    @Test
    void cancelledOrAbandonedCeremonyCannotAdvance() {
        final var before = state.get();
        subject.cancelPendingWork();
        subject.advanceCrsWork(NOW.plusSeconds(20), store, true);
        subject.addCrsPublication(publication(), NOW, store, 42);
        assertEquals(before, state.get());
        state.set(CRSState.DEFAULT);
        controller().advanceCrsWork(NOW, store, true);
        assertEquals(CRSState.DEFAULT, state.get());
        verifyNoInteractions(library);
    }

    @Test
    void asynchronousPublicationCapturesTheHeadAndRetriesFailure() {
        when(library.updateCrs(eq(SEED), any())).thenReturn(Bytes.wrap("nextproof"));
        when(submissions.submitCrsUpdate(9, 1, noThrowSha384HashOf(SEED), NEXT, PROOF))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")))
                .thenReturn(CompletableFuture.completedFuture(null));
        subject.advanceCrsWork(NOW, store, true);
        final var before = state.get();
        state.set(before.copyBuilder().crs(NEXT).build());
        tasks.remove().run();
        state.set(before);
        subject.advanceCrsWork(NOW, store, true);
        tasks.remove().run();
        verify(submissions, times(2)).submitCrsUpdate(9, 1, noThrowSha384HashOf(SEED), NEXT, PROOF);
    }
}
