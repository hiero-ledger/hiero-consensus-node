// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.history.HistoryLibrary.MISSING_SCHNORR_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.cryptography.wraps.SchnorrKeys;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Identity publications must not satisfy proof-key coverage for a newly certified generation. */
class ProofKeyIdentityPublicationTest {
    private static final long CONSTRUCTION_ID = 1L;
    private static final Instant START = Instant.EPOCH;
    private static final Bytes METADATA = Bytes.wrap("target-hints-key");
    private static final TssConfig CONFIG = DEFAULT_CONFIG.getConfigData(TssConfig.class);

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void identityCannotCompleteCoverageLiveOrAfterReconstruction(
            final boolean reconstructed, final boolean differentAttestation) {
        final var library = new HistoryLibraryImpl();
        final var weights = new TreeMap<Long, Long>();
        for (long id = 0; id < 7; id++) {
            weights.put(id, 1L);
        }
        final var hintsAuthority = new TreeMap<>(weights.headMap(5L));
        final var keys = new TreeMap<Long, SchnorrKeys>();
        for (final long id : List.of(0L, 3L, 4L, 5L, 6L)) {
            keys.put(id, library.newSchnorrKeyPair());
        }
        final var identity = MISSING_SCHNORR_KEY.toByteArray();
        if (differentAttestation) {
            identity[identity.length - 1] = 1;
        }
        final var publications = new ArrayList<ProofKeyPublication>();
        publications.add(new ProofKeyPublication(0L, Bytes.wrap(identity), START));
        for (final long id : List.of(3L, 4L, 5L, 6L)) {
            publications.add(new ProofKeyPublication(id, Bytes.wrap(keys.get(id).publicKey()), START));
        }
        final var construction = HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .sourceRosterHash(Bytes.wrap("genesis-roster"))
                .targetRosterHash(Bytes.wrap("genesis-roster"))
                .gracePeriodEndTime(asTimestamp(START.plusSeconds(1)))
                .build();
        final var persisted = new AtomicReference<>(construction);
        final var store = mock(WritableHistoryStore.class);
        given(store.getConstructionOrThrow(CONSTRUCTION_ID)).willAnswer(ignore -> persisted.get());
        given(store.setAssemblyTime(anyLong(), any())).willAnswer(invocation -> {
            final Instant cutoff = invocation.getArgument(1);
            final var assembled = persisted
                    .get()
                    .copyBuilder()
                    .assemblyStartTime(asTimestamp(cutoff))
                    .proofKeyCutoffTime(asTimestamp(cutoff))
                    .build();
            persisted.set(assembled);
            return assembled;
        });
        final var selectedKeys = new AtomicReference<Map<Long, Bytes>>();
        final HistoryProver.Factory proverFactory = (s, t, k, p, w, r, x, l, m) -> {
            selectedKeys.set(r);
            return mock(HistoryProver.class);
        };
        final var local = keys.get(3L);
        final var controller = new ProofControllerImpl(
                3L,
                new ProofKeysAccessorImpl.SchnorrKeyPair(Bytes.wrap(local.privateKey()), Bytes.wrap(local.publicKey())),
                construction,
                new RosterTransitionWeights(weights, weights),
                Runnable::run,
                mock(HistorySubmissions.class),
                new WrapsMpcStateMachine(),
                reconstructed ? publications : List.of(),
                List.of(),
                Map.of(),
                mock(HistoryService.class),
                library,
                proverFactory,
                null,
                mock(HistoryProofMetrics.class),
                CONFIG);
        if (!reconstructed) {
            publications.forEach(controller::addProofKeyPublication);
        }

        // Five publications are insufficient: identity 0 leaves only four real selected keys.
        // Committing this book could ground genesis (3 of 4), but its successor could not sign:
        // hinTS 0..4 and real Schnorr 3..6 intersect only at 3 and 4.
        assertEquals(Set.of(3L, 4L, 5L, 6L), selectedKeys.get().keySet());
        final var incompleteBook = book(weights, selectedKeys.get());
        assertFalse(new WrapsSigningQuorum(incompleteBook, hintsAuthority, true).canCompleteWithout(Set.of()));
        controller.advanceConstruction(START.plusSeconds(2), METADATA, store, false, CONFIG);
        verify(store, never()).setAssemblyTime(anyLong(), any());
        assertFalse(persisted.get().hasAssemblyStartTime());

        // Replacing the empty seat with node 0's real key restores both 5/7 coverage and a successor quorum.
        controller.addProofKeyPublication(
                new ProofKeyPublication(0L, Bytes.wrap(keys.get(0L).publicKey()), START.plusSeconds(3)));
        controller.advanceConstruction(START.plusSeconds(4), METADATA, store, false, CONFIG);

        verify(store).setAssemblyTime(CONSTRUCTION_ID, START.plusSeconds(4));
        assertEquals(Set.of(0L, 3L, 4L, 5L, 6L), selectedKeys.get().keySet());
        final var repairedQuorum = new WrapsSigningQuorum(book(weights, selectedKeys.get()), hintsAuthority, true);
        assertTrue(repairedQuorum.canCompleteWithout(Set.of()));
        assertTrue(repairedQuorum.isSatisfiedBy(Set.of(0L, 3L, 4L)));
    }

    private static AddressBook book(final TreeMap<Long, Long> weights, final Map<Long, Bytes> keys) {
        return AddressBook.from(
                weights, id -> keys.getOrDefault(id, MISSING_SCHNORR_KEY).toByteArray());
    }
}
