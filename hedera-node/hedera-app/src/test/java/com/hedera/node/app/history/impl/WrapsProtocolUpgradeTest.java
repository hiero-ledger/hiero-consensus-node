// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.history.impl.WritableHistoryStoreImpl.CURRENT_WRAPS_PROTOCOL_VERSION;
import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.PROOF_KEY_SETS_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.PROOF_VOTES_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.WRAPS_MESSAGE_HISTORIES_STATE_ID;
import static com.hedera.node.app.history.schemas.V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID;
import static com.hedera.node.app.history.schemas.V0800HistorySchema.HINTS_KEY_WITNESSES_STATE_ID;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.BOOTSTRAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercises protocol migration through the real store and controller, with native verification stubbed. */
class WrapsProtocolUpgradeTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_000);
    private static final Bytes A = Bytes.wrap("platform-roster");
    private static final Bytes B = Bytes.wrap("previous-hints-roster");
    private static final Bytes METADATA = Bytes.wrap("verification-key");
    private static final Bytes BOOK_HASH = Bytes.wrap("book-hash");
    private static final Bytes LEDGER_ID = Bytes.wrap("existing-trust-root");
    private static final TssConfig CONFIG = DEFAULT_CONFIG.getConfigData(TssConfig.class);

    @Test
    void replacingFullyBoundLegacyFreshGenesisFreesTheDecisiveVoterSlot() {
        final var targetWeights = new TreeMap<>(Map.of(0L, 80L, 1L, 10L, 2L, 10L));
        final var previousWeights = new TreeMap<>(Map.of(0L, 1L, 1L, 49L, 2L, 50L));
        final var weights = new RosterTransitionWeights(targetWeights, targetWeights);
        final var previous = new RosterTransitionWeights(previousWeights, targetWeights);
        // Under the old source authority, node 0's vote alone legitimately left the attempt unfinished.
        assertTrue(previous.sourceWeightOf(0) < previous.sourceWeightThreshold());
        assertTrue(weights.sourceWeightOf(1) + weights.sourceWeightOf(2) < weights.sourceWeightThreshold());
        final var keys = new TreeMap<>(Map.of(0L, Bytes.wrap("key0"), 1L, Bytes.wrap("key1"), 2L, Bytes.wrap("key2")));
        final var legacyProof = HistoryProof.newBuilder()
                .targetHistory(new History(BOOK_HASH, METADATA))
                .targetProofKeys(HistoryProver.proofKeyListFrom(keys))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("native-proof")))
                .build();
        final var currentProof = legacyProof
                .copyBuilder()
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(targetWeights))
                .build();
        final var completed = HistoryProofConstruction.newBuilder()
                .constructionId(100)
                .sourceRosterHash(B)
                .targetRosterHash(B)
                .targetProof(legacyProof)
                .build();
        final var legacy = HistoryProofConstruction.newBuilder()
                .constructionId(101)
                .sourceRosterHash(A)
                .targetRosterHash(A)
                .targetMetadata(METADATA)
                .proofKeyCutoffTime(asTimestamp(NOW))
                .wrapsSigningState(new WrapsSigningState(AGGREGATE, asTimestamp(NOW)))
                .build();
        final var store = store(completed, legacy);
        store.addProofVote(0, legacy.constructionId(), vote(legacyProof));
        final var library = mock(HistoryLibrary.class);
        when(library.hashAddressBook(any())).thenReturn(BOOK_HASH.toByteArray());
        when(library.ledgerIdOf(any())).thenReturn(LEDGER_ID);
        when(library.verifyCompressedProof(any(), any(), any())).thenReturn(true);
        final var publications = keys.entrySet().stream()
                .map(entry -> new ProofKeyPublication(entry.getKey(), entry.getValue(), NOW.minusSeconds(1)))
                .toList();

        // Without migration, replay occupies node 0's slot with a legacy proof lacking the membership snapshot.
        // Even corrected votes from all three online nodes cannot finish: the remaining weight is only 20/100.
        final var stale = controller(legacy, weights, publications, store, library);
        for (long nodeId = 0; nodeId < 3; nodeId++) {
            stale.addProofVote(nodeId, vote(currentProof), NOW.plusSeconds(1), store, CONFIG);
        }
        assertFalse(store.getNextConstruction().hasTargetProof());
        assertEquals(vote(legacyProof), store.getVotes(101, keys.keySet()).get(0L));

        final var rosters = mock(ActiveRosters.class);
        when(rosters.phase()).thenReturn(BOOTSTRAP);
        when(rosters.sourceRosterHash()).thenReturn(A);
        when(rosters.targetRosterHash()).thenReturn(A);
        when(rosters.findRelatedRoster(A))
                .thenReturn(new Roster(targetWeights.entrySet().stream()
                        .map(entry -> RosterEntry.newBuilder()
                                .nodeId(entry.getKey())
                                .weight(entry.getValue())
                                .build())
                        .toList()));
        final var replacement = store.getOrCreateConstruction(rosters, NOW.plusSeconds(2), CONFIG, true, METADATA);
        assertEquals(102, replacement.constructionId());
        assertEquals(CURRENT_WRAPS_PROTOCOL_VERSION, replacement.wrapsProtocolVersion());
        assertTrue(store.getVotes(101, keys.keySet()).isEmpty());
        assertEquals(completed, store.getActiveConstruction());
        assertEquals(LEDGER_ID, store.getLedgerId());

        final var assembling = store.setAssemblyTime(replacement.constructionId(), NOW.plusSeconds(3));
        final var rebuilt = controller(assembling, weights, publications, store, library);
        rebuilt.addProofVote(0, vote(currentProof), NOW.plusSeconds(4), store, CONFIG);
        assertEquals(currentProof, store.getNextConstruction().targetProof());
        assertEquals(completed, store.getActiveConstruction());
        assertEquals(LEDGER_ID, store.getLedgerId());
    }

    private static HistoryProofVote vote(final HistoryProof proof) {
        return HistoryProofVote.newBuilder().proof(proof).build();
    }

    private static ProofControllerImpl controller(
            final HistoryProofConstruction construction,
            final RosterTransitionWeights weights,
            final List<ProofKeyPublication> publications,
            final WritableHistoryStoreImpl store,
            final HistoryLibrary library) {
        return new ProofControllerImpl(
                0,
                new ProofKeysAccessorImpl.SchnorrKeyPair(Bytes.wrap("private"), Bytes.wrap("key0")),
                construction,
                weights,
                Runnable::run,
                mock(HistorySubmissions.class),
                new WrapsMpcStateMachine(),
                publications,
                List.of(),
                store.getVotes(construction.constructionId(), weights.sourceNodeIds()),
                mock(HistoryService.class),
                library,
                (s, t, k, p, w, r, x, l, m) -> mock(HistoryProver.class),
                null,
                mock(HistoryProofMetrics.class),
                CONFIG);
    }

    private static WritableHistoryStoreImpl store(
            final HistoryProofConstruction active, final HistoryProofConstruction next) {
        return new WritableHistoryStoreImpl(new MapWritableStates(Map.of(
                ACTIVE_PROOF_CONSTRUCTION_STATE_ID,
                singleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID, active),
                NEXT_PROOF_CONSTRUCTION_STATE_ID,
                singleton(NEXT_PROOF_CONSTRUCTION_STATE_ID, next),
                LEDGER_ID_STATE_ID,
                singleton(LEDGER_ID_STATE_ID, new ProtoBytes(LEDGER_ID)),
                PROOF_KEY_SETS_STATE_ID,
                new MapWritableKVState<>(PROOF_KEY_SETS_STATE_ID, "proofKeys"),
                PROOF_VOTES_STATE_ID,
                new MapWritableKVState<>(PROOF_VOTES_STATE_ID, "votes"),
                WRAPS_MESSAGE_HISTORIES_STATE_ID,
                new MapWritableKVState<>(WRAPS_MESSAGE_HISTORIES_STATE_ID, "messages"),
                HINTS_KEY_PROVENANCE_STATE_ID,
                ListWritableQueueState.<HintsKeyProvenance>builder(HINTS_KEY_PROVENANCE_STATE_ID, "provenance")
                        .build(),
                HINTS_KEY_WITNESSES_STATE_ID,
                new MapWritableKVState<ProtoLong, HintsKeyWitnessRecord>(HINTS_KEY_WITNESSES_STATE_ID, "witnesses"))));
    }

    private static <T> FunctionWritableSingletonState<T> singleton(final int id, final T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, "HistoryService." + id, value::get, value::set);
    }
}
