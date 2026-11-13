// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPreprocessingVoteTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.ReadableHintsStore.HintsKeyPublication;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsPreprocessingVoteHandler;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercises live/replay revalidation through the real service, controller manager and vote handler. */
class HintsPolicyRevalidationReplayTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567);
    private static final long NEXT_ID = 2L;
    private static final Bytes SOURCE_HASH = Bytes.wrap("source generation");
    private static final Bytes TARGET_HASH = Bytes.wrap("next generation");
    private static final Bytes CRS = Bytes.wrap("completed CRS");
    private static final Bytes BLOCK_HASH = Bytes.wrap("already opened block");
    private static final Bytes PARTIAL = Bytes.wrap("partial signature");
    private static final Bytes AGGREGATE = Bytes.wrap("existing compatibility signature");
    private static final PreprocessedKeys SOURCE_KEYS =
            new PreprocessedKeys(Bytes.wrap("source aggregation key"), Bytes.wrap("source verification key"));
    private static final PreprocessedKeys TARGET_KEYS =
            new PreprocessedKeys(Bytes.wrap("target aggregation key"), Bytes.wrap("target verification key"));
    private static final PreprocessingVote VOTE =
            PreprocessingVote.newBuilder().preprocessedKeys(TARGET_KEYS).build();
    private static final List<NodePartyId> SOURCE_PARTIES = LongStream.range(0, 7)
            .mapToObj(id -> new NodePartyId(id, (int) id + 1, 1L))
            .toList();
    private static final HintsConstruction ACTIVE = HintsConstruction.newBuilder()
            .constructionId(1L)
            .sourceRosterHash(SOURCE_HASH)
            .targetRosterHash(SOURCE_HASH)
            .crsId(1L)
            .numParties(16)
            .hintsScheme(new HintsScheme(SOURCE_KEYS, SOURCE_PARTIES))
            .build();
    private static final HintsConstruction UPCOMING = HintsConstruction.newBuilder()
            .constructionId(NEXT_ID)
            .sourceRosterHash(SOURCE_HASH)
            .targetRosterHash(TARGET_HASH)
            .crsId(1L)
            .numParties(16)
            .preprocessingStartTime(asTimestamp(NOW))
            .build();
    private static final CRSState CRS_STATE = CRSState.newBuilder()
            .ceremonyId(1L)
            .numParties(16)
            .stage(COMPLETED)
            .crs(CRS)
            .build();

    private enum InvalidSource {
        MISSING_LEGACY_SNAPSHOT,
        MISMATCHED_NONEMPTY_SNAPSHOT
    }

    @ParameterizedTest
    @EnumSource(InvalidSource.class)
    void failedStrictRevalidationMakesWarmAndColdVoteHandlingIdentical(final InvalidSource invalidSource) {
        final var invalidHistory = invalidHistory(invalidSource);
        final var warm = new Harness(true, Map.of());
        warm.reconcile(invalidHistory);
        final var oldController = warm.controllers.getInProgressById(NEXT_ID).orElseThrow();
        final var oldSigning = warm.signingContext.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        warm.vote(0L);
        warm.vote(1L);
        assertEquals(List.of("vote:0", "vote:1"), warm.writes);
        assertFalse(warm.next.get().hasHintsScheme(), "Two votes are below the S7 threshold of three");
        final var cold = new Harness(false, warm.persistedVotes);
        warm.config.set(configuration(false));
        warm.writes.clear();
        assertThrows(HintsGenerationWeights.InvalidGenerationException.class, () -> warm.reconcile(invalidHistory));
        assertThrows(HintsGenerationWeights.InvalidGenerationException.class, () -> cold.reconcile(invalidHistory));

        // A stale warm controller would accept this as the winning third vote; a cold node has none.
        warm.vote(2L);
        cold.vote(2L);
        assertEquals(List.of(), warm.writes);
        assertEquals(warm.writes, cold.writes);
        assertEquals(warm.persistedVotes, cold.persistedVotes);
        assertFalse(warm.next.get().hasHintsScheme());
        assertFalse(cold.next.get().hasHintsScheme());
        assertTrue(warm.controllers.getAnyInProgress().isEmpty());
        assertTrue(cold.controllers.getAnyInProgress().isEmpty());
        assertTrue(warm.controllers.getInProgressById(NEXT_ID).isEmpty());
        assertTrue(cold.controllers.getInProgressById(NEXT_ID).isEmpty());
        assertFalse(oldController.isStillInProgress(), "The warm controller must also be cancelled");
        assertFalse(warm.signingContext.isReady());

        // Controller cleanup must leave attempts that already captured their signing policy intact.
        for (long id = 0; id < 3; id++) oldSigning.incorporateValid(CRS, id, PARTIAL);
        assertFalse(oldSigning.future().isDone(), "The open S7 signing still needs four shares");
        oldSigning.incorporateValid(CRS, 3L, PARTIAL);
        assertEquals(AGGREGATE, oldSigning.future().getNow(null));
        warm.reconcile(validHistory());
        cold.reconcile(validHistory());
        assertTrue(warm.signingContext.isReady());
        assertTrue(cold.signingContext.isReady());
        warm.vote(2L);
        cold.vote(2L);
        assertEquals(List.of("vote:2"), warm.writes);
        assertEquals(warm.writes, cold.writes);
        assertFalse(warm.next.get().hasHintsScheme(), "Three votes are below the F10 threshold of four");
        assertFalse(cold.next.get().hasHintsScheme());
        warm.vote(3L);
        cold.vote(3L);
        assertEquals(List.of("vote:2", "vote:3", "scheme:2"), warm.writes);
        assertEquals(warm.writes, cold.writes);
        assertEquals(warm.persistedVotes, cold.persistedVotes);
        assertEquals(warm.next.get(), cold.next.get());
        assertTrue(warm.next.get().hasHintsScheme());
    }

    private static HistoryProofConstruction invalidHistory(final InvalidSource kind) {
        final var valid = validHistory();
        return switch (kind) {
            case MISSING_LEGACY_SNAPSHOT ->
                valid.copyBuilder()
                        .targetProof(valid.targetProofOrThrow().copyBuilder().targetNodeWeights(List.of()))
                        .build();
            case MISMATCHED_NONEMPTY_SNAPSHOT ->
                valid.copyBuilder()
                        .targetProof(valid.targetProofOrThrow()
                                .copyBuilder()
                                .targetHistory(
                                        new History(Bytes.EMPTY, Bytes.wrap("another generation's verification key"))))
                        .build();
        };
    }

    private static HistoryProofConstruction validHistory() {
        return HistoryProofConstruction.newBuilder()
                .constructionId(1L)
                .sourceRosterHash(SOURCE_HASH)
                .targetRosterHash(SOURCE_HASH)
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(new History(Bytes.EMPTY, SOURCE_KEYS.verificationKey()))
                        .uncompressedWrapsProof(Bytes.wrap("persisted WRAPS proof"))
                        .targetNodeWeights(LongStream.range(0, 10)
                                .mapToObj(id -> new ProofNodeWeight(id, 1L))
                                .toList()))
                .build();
    }

    private static Configuration configuration(final boolean compatibility) {
        return HederaTestConfigBuilder.create()
                .withValue("tss.hintsEnabled", true)
                .withValue("tss.historyEnabled", true)
                .withValue("tss.forceMockSignatures", compatibility)
                .getOrCreateConfig();
    }

    private static final class Harness {
        private final AtomicReference<Configuration> config;
        private final AtomicReference<HintsConstruction> next = new AtomicReference<>(UPCOMING);
        private final Map<Long, PreprocessingVote> persistedVotes = new TreeMap<>();
        private final List<String> writes = new ArrayList<>();
        private final WritableHintsStore store = mock(WritableHintsStore.class);
        private final ActiveRosters rosters;
        private final HintsContext signingContext;
        private final HintsControllers controllers;
        private final HintsServiceImpl service;
        private final HintsPreprocessingVoteHandler handler;

        private Harness(final boolean compatibility, final Map<Long, PreprocessingVote> initialVotes) {
            config = new AtomicReference<>(configuration(compatibility));
            persistedVotes.putAll(initialVotes);
            final var library = mock(HintsLibrary.class);
            when(library.validateHintsKey(any(), any(), anyInt(), anyInt())).thenReturn(true);
            when(library.aggregateSignatures(any(), any(), any(), any())).thenReturn(AGGREGATE);
            signingContext = new HintsContext(library, config::get, mock(HintsSigningMetrics.class));
            final var accessor = mock(HintsKeyAccessor.class);
            when(accessor.getOrCreateBlsPrivateKey(NEXT_ID)).thenReturn(Bytes.wrap("private key"));
            final var self = mock(NodeInfo.class);
            when(self.nodeId()).thenReturn(0L);
            controllers = new HintsControllers(
                    Runnable::run,
                    accessor,
                    library,
                    mock(HintsSubmissions.class),
                    signingContext,
                    () -> self,
                    config::get,
                    (s, c, context) -> {});
            final var component = mock(HintsServiceComponent.class);
            when(component.signingContext()).thenReturn(signingContext);
            when(component.controllers()).thenReturn(controllers);
            service = new HintsServiceImpl(component, library);
            handler = new HintsPreprocessingVoteHandler(controllers);
            final var roster = new Roster(LongStream.range(0, 10)
                    .mapToObj(
                            id -> RosterEntry.newBuilder().nodeId(id).weight(1L).build())
                    .toList());
            final var rosterStore = mock(ReadableRosterStore.class);
            when(rosterStore.getActiveRosterHash()).thenReturn(SOURCE_HASH);
            when(rosterStore.getCandidateRosterHash()).thenReturn(TARGET_HASH);
            when(rosterStore.get(SOURCE_HASH)).thenReturn(roster);
            when(rosterStore.get(TARGET_HASH)).thenReturn(roster);
            rosters = ActiveRosters.from(rosterStore, false, () -> false, null);
            when(store.getActiveConstruction()).thenReturn(ACTIVE);
            when(store.getNextConstruction()).thenAnswer(invocation -> next.get());
            when(store.getOrCreateConstruction(eq(rosters), any(), any())).thenAnswer(invocation -> next.get());
            when(store.getCrsState()).thenReturn(CRS_STATE);
            when(store.getCrsStateFor(any())).thenReturn(CRS_STATE);
            when(store.getHintsKeyPublications(anySet(), eq(16), eq(1L)))
                    .thenReturn(LongStream.range(0, 7)
                            .mapToObj(id -> new HintsKeyPublication(
                                    id,
                                    Bytes.wrap("target hint " + id),
                                    (int) id + 1,
                                    NOW.minusSeconds(1).plusNanos(id)))
                            .toList());
            when(store.getVotes(eq(NEXT_ID), anySet())).thenAnswer(invocation -> {
                final Set<Long> nodeIds = invocation.getArgument(1);
                final var votes = new TreeMap<Long, PreprocessingVote>();
                persistedVotes.forEach((nodeId, vote) -> {
                    if (nodeIds.contains(nodeId)) votes.put(nodeId, vote);
                });
                return votes;
            });
            doAnswer(invocation -> {
                        final long nodeId = invocation.getArgument(0);
                        persistedVotes.put(nodeId, invocation.getArgument(2));
                        writes.add("vote:" + nodeId);
                        return null;
                    })
                    .when(store)
                    .addPreprocessingVote(anyLong(), eq(NEXT_ID), any());
            when(store.setHintsScheme(eq(NEXT_ID), any(), anyMap(), anyMap())).thenAnswer(invocation -> {
                final Map<Long, Integer> partyIds = invocation.getArgument(2);
                final Map<Long, Long> weights = invocation.getArgument(3);
                final var parties = new TreeMap<>(partyIds)
                        .entrySet().stream()
                                .map(entry ->
                                        new NodePartyId(entry.getKey(), entry.getValue(), weights.get(entry.getKey())))
                                .toList();
                final var completed = next.get()
                        .copyBuilder()
                        .hintsScheme(new HintsScheme(invocation.getArgument(1), parties))
                        .build();
                next.set(completed);
                writes.add("scheme:" + NEXT_ID);
                return completed;
            });
        }

        private void reconcile(final HistoryProofConstruction history) {
            service.reconcile(rosters, store, NOW, NOW, config.get().getConfigData(TssConfig.class), false, history);
        }

        private void vote(final long nodeId) {
            final var context = mock(HandleContext.class);
            when(context.body())
                    .thenReturn(TransactionBody.newBuilder()
                            .hintsPreprocessingVote(new HintsPreprocessingVoteTransactionBody(NEXT_ID, VOTE))
                            .build());
            final var creator = mock(NodeInfo.class);
            when(creator.nodeId()).thenReturn(nodeId);
            when(context.creatorInfo()).thenReturn(creator);
            final var stores = mock(StoreFactory.class);
            when(stores.writableStore(WritableHintsStore.class)).thenReturn(store);
            when(context.storeFactory()).thenReturn(stores);
            handler.handle(context);
        }
    }
}
