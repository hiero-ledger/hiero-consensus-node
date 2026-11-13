// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.history.impl.WritableHistoryStoreImpl.CURRENT_WRAPS_PROTOCOL_VERSION;
import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.PROOF_KEY_SETS_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.PROOF_VOTES_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.WRAPS_MESSAGE_HISTORIES_STATE_ID;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.TRANSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercises replacement-key publication through the controller and the real store's next-key adoption. */
class ProofKeyRepublicationTest {
    private static final long SELF_ID = 0L;
    private static final Instant NOW = Instant.ofEpochSecond(1_000);
    private static final Bytes A = Bytes.wrap("source-roster");
    private static final Bytes B = Bytes.wrap("target-roster");
    private static final Bytes C = Bytes.wrap("next-target-roster");
    private static final Bytes METADATA = Bytes.wrap("verification-key");
    private static final Bytes BOOK_HASH = Bytes.wrap("book-hash");
    private static final Bytes LEDGER_ID = Bytes.wrap("existing-trust-root");
    private static final Bytes OLD_KEY = Bytes.wrap("old-self-key");
    private static final Bytes REPLACEMENT_KEY = Bytes.wrap("replacement-self-key");
    private static final TssConfig CONFIG = DEFAULT_CONFIG.getConfigData(TssConfig.class);
    private static final TreeMap<Long, Long> WEIGHTS = new TreeMap<>(Map.of(0L, 1L, 1L, 1L, 2L, 1L));
    private static final Map<Long, Bytes> ORIGINAL_KEYS =
            Map.of(SELF_ID, OLD_KEY, 1L, Bytes.wrap("key1"), 2L, Bytes.wrap("key2"));
    private static final Roster ROSTER = new Roster(WEIGHTS.entrySet().stream()
            .map(entry -> RosterEntry.newBuilder()
                    .nodeId(entry.getKey())
                    .weight(entry.getValue())
                    .build())
            .toList());

    @Test
    void republishesBeforeEarlyAssemblyAndAdoptsTheReplacementOnlyInTheNextConstruction() {
        final var originalProof = proof(ORIGINAL_KEYS);
        final var active = HistoryProofConstruction.newBuilder()
                .constructionId(100L)
                .wrapsProtocolVersion(CURRENT_WRAPS_PROTOCOL_VERSION)
                .sourceRosterHash(A)
                .targetRosterHash(A)
                .targetProof(originalProof)
                .build();
        final var store = store(active);
        ORIGINAL_KEYS.forEach((nodeId, key) -> assertTrue(store.setProofKey(nodeId, key, NOW.minusSeconds(1))));
        final var collecting = store.getOrCreateConstruction(rosters(A, B), NOW, CONFIG, false, METADATA);
        final var submissions = mock(HistorySubmissions.class);
        when(submissions.submitProofKeyPublication(REPLACEMENT_KEY))
                .thenReturn(CompletableFuture.completedFuture(null));
        final var currentTargetKeys = new AtomicReference<Map<Long, Bytes>>();
        final var current = controller(collecting, originalProof, store, submissions, currentTargetKeys);

        // All target keys already exist, so the fast assembly path must still publish a replacement local key.
        current.advanceConstruction(NOW, METADATA, store, true, CONFIG);
        assertTrue(store.getNextConstruction().hasAssemblyStartTime());
        verify(submissions, times(1)).submitProofKeyPublication(REPLACEMENT_KEY);

        // An existing key's consensus publication is queued for a later construction. The handler does not
        // forward it to the current controller, because setProofKey returns false for a replacement.
        assertFalse(store.setProofKey(SELF_ID, REPLACEMENT_KEY, NOW.plusSeconds(1)));
        assertEquals(OLD_KEY, keyOf(store, SELF_ID));
        current.advanceConstruction(NOW.plusSeconds(2), METADATA, store, true, CONFIG);
        assertEquals(ORIGINAL_KEYS, currentTargetKeys.get());
        assertEquals(originalProof, store.getActiveConstruction().targetProofOrThrow());
        verify(submissions, times(1)).submitProofKeyPublication(REPLACEMENT_KEY);

        // Once the remaining eligible signers finish this generation, its frozen book still has the old key.
        // The pending replacement becomes selectable when the next construction starts, without rewriting
        // that completed predecessor or the ledger's trust root.
        final var completedProof = proof(currentTargetKeys.get());
        store.completeProof(collecting.constructionId(), completedProof);
        assertTrue(store.handoff(ROSTER, ROSTER, B));
        final var next = store.getOrCreateConstruction(rosters(B, C), NOW.plusSeconds(3), CONFIG, false, METADATA);
        assertEquals(REPLACEMENT_KEY, keyOf(store, SELF_ID));
        assertEquals(completedProof, store.getActiveConstruction().targetProofOrThrow());
        assertEquals(OLD_KEY, completedProof.targetProofKeys().getFirst().key());
        assertEquals(LEDGER_ID, store.getLedgerId());

        final var nextTargetKeys = new AtomicReference<Map<Long, Bytes>>();
        final var nextController = controller(next, completedProof, store, submissions, nextTargetKeys);
        nextController.advanceConstruction(NOW.plusSeconds(3), METADATA, store, true, CONFIG);
        nextController.advanceConstruction(NOW.plusSeconds(4), METADATA, store, true, CONFIG);
        final var expected = new TreeMap<>(ORIGINAL_KEYS);
        expected.put(SELF_ID, REPLACEMENT_KEY);
        assertEquals(expected, nextTargetKeys.get());
        verify(submissions, times(1)).submitProofKeyPublication(REPLACEMENT_KEY);
    }

    private static ProofControllerImpl controller(
            final HistoryProofConstruction construction,
            final HistoryProof sourceProof,
            final WritableHistoryStoreImpl store,
            final HistorySubmissions submissions,
            final AtomicReference<Map<Long, Bytes>> targetKeys) {
        final var library = mock(HistoryLibrary.class);
        when(library.hashAddressBook(any())).thenReturn(BOOK_HASH.toByteArray());
        final var prover = mock(HistoryProver.class);
        when(prover.advance(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    targetKeys.set(Map.copyOf(invocation.<Map<Long, Bytes>>getArgument(3)));
                    return HistoryProver.Outcome.InProgress.INSTANCE;
                });
        return new ProofControllerImpl(
                SELF_ID,
                new ProofKeysAccessorImpl.SchnorrKeyPair(Bytes.wrap("replacement-private"), REPLACEMENT_KEY),
                construction,
                new RosterTransitionWeights(WEIGHTS, WEIGHTS),
                Runnable::run,
                submissions,
                new WrapsMpcStateMachine(),
                store.getProofKeyPublications(WEIGHTS.keySet()),
                List.of(),
                Map.of(),
                mock(HistoryService.class),
                library,
                (s, t, k, p, w, r, x, l, m) -> prover,
                sourceProof,
                mock(HistoryProofMetrics.class),
                CONFIG);
    }

    private static HistoryProof proof(final Map<Long, Bytes> keys) {
        return HistoryProof.newBuilder()
                .targetHistory(new History(BOOK_HASH, METADATA))
                .targetProofKeys(HistoryProver.proofKeyListFrom(keys))
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(WEIGHTS))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("compressed-proof")))
                .uncompressedWrapsProof(Bytes.wrap("uncompressed-proof"))
                .build();
    }

    private static Bytes keyOf(final WritableHistoryStoreImpl store, final long nodeId) {
        return store.getProofKeyPublications(Set.of(nodeId)).getFirst().proofKey();
    }

    private static ActiveRosters rosters(final Bytes sourceHash, final Bytes targetHash) {
        final var rosters = mock(ActiveRosters.class);
        when(rosters.phase()).thenReturn(TRANSITION);
        when(rosters.sourceRosterHash()).thenReturn(sourceHash);
        when(rosters.targetRosterHash()).thenReturn(targetHash);
        when(rosters.findRelatedRoster(any())).thenReturn(ROSTER);
        return rosters;
    }

    private static WritableHistoryStoreImpl store(final HistoryProofConstruction active) {
        return new WritableHistoryStoreImpl(new MapWritableStates(Map.of(
                ACTIVE_PROOF_CONSTRUCTION_STATE_ID,
                singleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID, active),
                NEXT_PROOF_CONSTRUCTION_STATE_ID,
                singleton(NEXT_PROOF_CONSTRUCTION_STATE_ID, HistoryProofConstruction.DEFAULT),
                LEDGER_ID_STATE_ID,
                singleton(LEDGER_ID_STATE_ID, new ProtoBytes(LEDGER_ID)),
                PROOF_KEY_SETS_STATE_ID,
                new MapWritableKVState<>(PROOF_KEY_SETS_STATE_ID, "proofKeys"),
                PROOF_VOTES_STATE_ID,
                new MapWritableKVState<>(PROOF_VOTES_STATE_ID, "votes"),
                WRAPS_MESSAGE_HISTORIES_STATE_ID,
                new MapWritableKVState<>(WRAPS_MESSAGE_HISTORIES_STATE_ID, "messages"))));
    }

    private static <T> FunctionWritableSingletonState<T> singleton(final int id, final T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, "HistoryService." + id, value::get, value::set);
    }
}
