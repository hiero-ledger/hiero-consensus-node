// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static com.hedera.node.app.hints.schemas.V059HintsSchema.*;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.NEXT_CRS_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.*;
import static com.hedera.node.app.history.schemas.V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.WritableHintsStoreImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.record.TraceDataSizeLimiter;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.CommittableWritableStates;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TssProvenanceHandoffTest {
    private static final Bytes ROSTER_HASH = Bytes.wrap("roster");
    private static final Bytes SOURCE_KEY = Bytes.wrap("source-key");
    private static final Bytes TARGET_KEY = Bytes.wrap("target-key");
    private static final Bytes THIRD_KEY = Bytes.wrap("third-key");
    private final WritableEntityIdStore counters = mock(WritableEntityIdStore.class);
    private final HistoryService historyService = mock(HistoryService.class);
    private final HintsService hintsService = mock(HintsService.class);

    @Test
    void rollbackAndCommitKeepQueueAndBothConstructionsAtomicAcrossConsecutiveRotations() {
        final var state = initializedState();
        final var first = provenance(1, 2, SOURCE_KEY, TARGET_KEY);
        prepareNext(state, first);
        final var stack = stackFor(state);
        when(hintsService.handoff(any(), any(), any(), any()))
                .thenAnswer(invocation -> ((WritableHintsStore) invocation.getArgument(0))
                        .handoff(invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));

        assertTrue(handoff(stack));
        assertEquals(first, historyIn(stack).keyProvenance().next());
        assertEquals(2L, hintsIn(stack).getActiveConstruction().constructionId());
        assertEquals(2L, historyIn(stack).getActiveConstruction().constructionId());
        assertFalse(historyIn(state).keyProvenance().hasNext());
        assertEquals(1L, hintsIn(state).getActiveConstruction().constructionId());
        // A later failure in this same transaction reverts the queue and both promotions.
        stack.rollbackFullStack();
        assertFalse(historyIn(stack).keyProvenance().hasNext());
        assertEquals(1L, historyIn(stack).getActiveConstruction().constructionId());
        assertEquals(1L, hintsIn(stack).getActiveConstruction().constructionId());
        assertEquals(2L, historyIn(stack).getNextConstruction().constructionId());
        assertEquals(2L, hintsIn(stack).getNextConstruction().constructionId());

        assertTrue(handoff(stack));
        stack.commitFullStack();
        assertEquals(2L, historyIn(state).getActiveConstruction().constructionId());
        assertEquals(2L, hintsIn(state).getActiveConstruction().constructionId());
        assertEquals(HistoryProofConstruction.DEFAULT, historyIn(state).getNextConstruction());
        assertEquals(HintsConstruction.DEFAULT, hintsIn(state).getNextConstruction());
        assertFalse(handoff(stack));

        final var second = provenance(2, 3, TARGET_KEY, THIRD_KEY);
        prepareNext(state, second);
        final var nextStack = stackFor(state);
        assertTrue(handoff(nextStack));
        nextStack.commitFullStack();
        final var links = historyIn(state).keyProvenance();
        assertEquals(first, links.next());
        assertEquals(second, links.next());
        assertFalse(links.hasNext());
        assertEquals(3L, hintsIn(state).getActiveConstruction().constructionId());
        assertEquals(3L, historyIn(state).getActiveConstruction().constructionId());
    }

    private boolean handoff(final SavepointStackImpl stack) {
        return TssHandoffCoordinator.tryJointHandoff(
                historyIn(stack),
                hintsIn(stack),
                historyService,
                hintsService,
                Roster.DEFAULT,
                Roster.DEFAULT,
                ROSTER_HASH);
    }

    private WritableHistoryStoreImpl historyIn(final com.swirlds.state.State state) {
        return new WritableHistoryStoreImpl(state.getWritableStates(HistoryService.NAME));
    }

    private WritableHintsStoreImpl hintsIn(final com.swirlds.state.State state) {
        return new WritableHintsStoreImpl(state.getWritableStates(HintsService.NAME), counters);
    }

    private SavepointStackImpl stackFor(final FakeState state) {
        final var stack = SavepointStackImpl.newRootStack(
                state,
                3,
                50,
                mock(BoundaryStateChangeListener.class),
                mock(ImmediateStateChangeListener.class),
                StreamMode.RECORDS,
                TraceDataSizeLimiter.NO_LIMIT);
        stack.getBaseBuilder(StreamBuilder.class).status(ResponseCodeEnum.SUCCESS);
        return stack;
    }

    private static FakeState initializedState() {
        final var state = new FakeState();
        state.addService(
                HistoryService.NAME,
                Map.of(
                        LEDGER_ID_STATE_ID, new AtomicReference<>(ProtoBytes.DEFAULT),
                        ACTIVE_PROOF_CONSTRUCTION_STATE_ID,
                                new AtomicReference<>(HistoryProofConstruction.newBuilder()
                                        .constructionId(1L)
                                        .build()),
                        NEXT_PROOF_CONSTRUCTION_STATE_ID, new AtomicReference<>(HistoryProofConstruction.DEFAULT),
                        PROOF_KEY_SETS_STATE_ID, new ConcurrentHashMap<>(),
                        PROOF_VOTES_STATE_ID, new ConcurrentHashMap<>(),
                        WRAPS_MESSAGE_HISTORIES_STATE_ID, new ConcurrentHashMap<>(),
                        HINTS_KEY_PROVENANCE_STATE_ID, new ConcurrentLinkedDeque<>()));
        state.addService(
                HintsService.NAME,
                Map.of(
                        ACTIVE_HINTS_CONSTRUCTION_STATE_ID, new AtomicReference<>(hints(1, SOURCE_KEY)),
                        NEXT_HINTS_CONSTRUCTION_STATE_ID, new AtomicReference<>(HintsConstruction.DEFAULT),
                        HINTS_KEY_SETS_STATE_ID, new ConcurrentHashMap<>(),
                        PREPROCESSING_VOTES_STATE_ID, new ConcurrentHashMap<>(),
                        CRS_STATE_STATE_ID,
                                new AtomicReference<>(CRSState.newBuilder()
                                        .ceremonyId(1L)
                                        .numParties(8)
                                        .stage(CRSStage.COMPLETED)
                                        .crs(Bytes.wrap(new byte[304 + 288 * 8]))
                                        .build()),
                        NEXT_CRS_STATE_ID, new AtomicReference<>(CRSState.DEFAULT)));
        return state;
    }

    private static void prepareNext(final FakeState state, final HintsKeyProvenance provenance) {
        final var historyStates = state.getWritableStates(HistoryService.NAME);
        final var proof = HistoryProof.newBuilder()
                .targetHistory(History.newBuilder().metadata(provenance.targetVerificationKey()))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("proof")))
                .uncompressedWrapsProof(Bytes.wrap("uncompressed"))
                .keyProvenanceSignature(provenance.signature())
                .build();
        historyStates
                .getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID)
                .put(HistoryProofConstruction.newBuilder()
                        .constructionId(provenance.historyConstructionId())
                        .targetRosterHash(ROSTER_HASH)
                        .targetMetadata(provenance.targetVerificationKey())
                        .targetProof(proof)
                        .keyProvenance(provenance)
                        .build());
        ((CommittableWritableStates) historyStates).commit();
        final var hintsStates = state.getWritableStates(HintsService.NAME);
        hintsStates
                .getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                .put(hints(provenance.targetHintsConstructionId(), provenance.targetVerificationKey()));
        ((CommittableWritableStates) hintsStates).commit();
    }

    private static HintsConstruction hints(final long id, final Bytes key) {
        return HintsConstruction.newBuilder()
                .constructionId(id)
                .targetRosterHash(ROSTER_HASH)
                .crsId(1L)
                .numParties(8)
                .hintsScheme(new HintsScheme(new PreprocessedKeys(Bytes.wrap(new byte[49]), key), List.of()))
                .build();
    }

    private static HintsKeyProvenance provenance(
            final long source, final long target, final Bytes oldKey, final Bytes newKey) {
        return HintsKeyProvenance.newBuilder()
                .sourceHintsConstructionId(source)
                .targetHintsConstructionId(target)
                .historyConstructionId(target)
                .sourceVerificationKey(oldKey)
                .targetVerificationKey(newKey)
                .signature(Bytes.wrap("signature-" + target))
                .build();
    }
}
