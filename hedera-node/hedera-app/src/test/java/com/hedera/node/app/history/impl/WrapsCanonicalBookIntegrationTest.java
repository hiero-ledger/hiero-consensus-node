// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.cryptography.wraps.SchnorrKeys;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Exercises the production prover, not just native adapters, across a sparse hinTS/full-book boundary. */
class WrapsCanonicalBookIntegrationTest {
    private final HistoryLibrary library = new HistoryLibraryImpl();
    private final SplittableRandom random = new SplittableRandom(28_214);

    @Test
    void canonicalBooksGroundAndExtendWithSparseHintsAndDailyStakeChanges() throws Exception {
        final var keys = new TreeMap<Long, SchnorrKeys>();
        for (final long nodeId : List.of(0L, 1L, 2L, 5L, 6L)) {
            keys.put(nodeId, library.newSchnorrKeyPair());
        }
        final var originalWeights = weights(10, 10, 10, 10, 10, 10, 10);
        final var genesisBook = book(originalWeights, keys);
        final var genesisKey = verificationKey();
        final var ledgerId = Bytes.wrap(library.computeWrapsMessage(genesisBook, genesisKey.toByteArray()));

        // Node 5 follows two absent hinTS/Schnorr publishers. The old source map moved its signer bit to node 3.
        final var genesis = prove(
                1,
                null,
                genesisBook,
                participatingWeights(originalWeights, keys.keySet()),
                originalWeights,
                keys,
                genesisKey,
                Set.of(0L, 1L, 5L),
                null);
        assertTrue(library.verifyCompressedProof(
                genesis.chainOfTrustProofOrThrow().wrapsProofOrThrow().toByteArray(),
                ledgerId.toByteArray(),
                genesisKey.toByteArray()));
        assertEquals(
                originalWeights, HistoryProofAddressBooks.targetWeights(genesis).orElseThrow());

        // Only the TSS generation changes. No platform roster is needed to recover the predecessor book.
        final var firstDayWeights = weights(10, 20, 30, 40, 50, 40, 50);
        final var firstDayKey = verificationKey();
        final var firstDay = prove(
                2,
                roundTrip(genesis),
                genesisBook,
                participatingWeights(originalWeights, keys.keySet()),
                firstDayWeights,
                keys,
                firstDayKey,
                Set.of(0L, 1L, 5L),
                ledgerId);
        assertTrue(library.verifyCompressedProof(
                firstDay.chainOfTrustProofOrThrow().wrapsProofOrThrow().toByteArray(),
                ledgerId.toByteArray(),
                firstDayKey.toByteArray()));

        // A proof written before the snapshot existed is enriched only by weights that reproduce its commitment:
        // the generation's complete listed weights do, while its sparse hinTS weights cannot
        final var legacy = firstDay.copyBuilder().targetNodeWeights(List.of()).build();
        assertEquals(firstDay, HistoryProofAddressBooks.withTargetWeights(legacy, firstDayWeights, library));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryProofAddressBooks.withTargetWeights(
                        legacy, participatingWeights(firstDayWeights, keys.keySet()), library));

        // After another candidate replaces the first day's roster, the serialized proof retains all its weights,
        // including nonzero listed weights for sentinel nodes 3 and 4. Its hinTS authority uses the adopted weights.
        final var restored = roundTrip(firstDay);
        assertEquals(
                firstDayWeights,
                HistoryProofAddressBooks.targetWeights(restored).orElseThrow());
        final var secondDayKey = verificationKey();
        final var secondDay = prove(
                3,
                restored,
                HistoryProofAddressBooks.targetBook(restored),
                participatingWeights(firstDayWeights, keys.keySet()),
                weights(20, 30, 40, 50, 60, 50, 60),
                keys,
                secondDayKey,
                Set.of(0L, 5L, 6L),
                ledgerId);
        assertTrue(library.verifyCompressedProof(
                secondDay.chainOfTrustProofOrThrow().wrapsProofOrThrow().toByteArray(),
                ledgerId.toByteArray(),
                secondDayKey.toByteArray()));
    }

    @Test
    void repairedCoverageWithDifferentHintsAndSchnorrAvailabilitySupportsItsSuccessor() throws Exception {
        final var keys = new TreeMap<Long, SchnorrKeys>();
        for (final long nodeId : List.of(0L, 3L, 4L, 5L, 6L)) {
            keys.put(nodeId, library.newSchnorrKeyPair());
        }
        final var originalWeights = weights(1, 1, 1, 1, 1, 1, 1);
        final var hintsAuthority = participatingWeights(originalWeights, Set.of(0L, 1L, 2L, 3L, 4L));
        final var canonicalBook = book(originalWeights, keys);
        final var genesisKey = verificationKey();
        final var ledgerId = Bytes.wrap(library.computeWrapsMessage(canonicalBook, genesisKey.toByteArray()));

        // The controller regression requires node 0's real key to replace its identity publication before assembly.
        // The hinTS and Schnorr sets now each cover 5/7 weight, but their membership is deliberately different.
        final var genesis = prove(
                10, null, canonicalBook, hintsAuthority, originalWeights, keys, genesisKey, Set.of(3L, 4L, 5L), null);
        assertTrue(library.verifyCompressedProof(
                genesis.chainOfTrustProofOrThrow().wrapsProofOrThrow().toByteArray(),
                ledgerId.toByteArray(),
                genesisKey.toByteArray()));

        // A different frozen set must be available for the successor: only 0, 3 and 4 have both authorities.
        final var restored = roundTrip(genesis);
        final var successorSigners = Set.of(0L, 3L, 4L);
        assertTrue(new WrapsSigningQuorum(HistoryProofAddressBooks.targetBook(restored), hintsAuthority, true)
                .isSatisfiedBy(successorSigners));
        final var successorKey = verificationKey();
        final var successor = prove(
                11,
                restored,
                HistoryProofAddressBooks.targetBook(restored),
                hintsAuthority,
                originalWeights,
                keys,
                successorKey,
                successorSigners,
                ledgerId);
        assertTrue(library.verifyCompressedProof(
                successor.chainOfTrustProofOrThrow().wrapsProofOrThrow().toByteArray(),
                ledgerId.toByteArray(),
                successorKey.toByteArray()));
    }

    private HistoryProof prove(
            final long constructionId,
            final HistoryProof sourceProof,
            final AddressBook sourceBook,
            final SortedMap<Long, Long> hintsWeights,
            final SortedMap<Long, Long> targetWeights,
            final SortedMap<Long, SchnorrKeys> keys,
            final Bytes targetKey,
            final Set<Long> signers,
            final Bytes ledgerId) {
        final var submissions = mock(HistorySubmissions.class);
        given(submissions.submitExplicitProofVote(anyLong(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        final var config = mock(TssConfig.class);
        given(config.wrapsVoteJitterPerRank()).willReturn(Duration.ZERO);
        final var targetKeys = new TreeMap<Long, Bytes>();
        keys.forEach((id, key) -> targetKeys.put(id, Bytes.wrap(key.publicKey())));
        final long selfId = signers.stream().min(Long::compareTo).orElseThrow();
        final var ownKey = keys.get(selfId);
        final var prover = new WrapsHistoryProver(
                selfId,
                Duration.ofSeconds(30),
                new ProofKeysAccessorImpl.SchnorrKeyPair(
                        Bytes.wrap(ownKey.privateKey()), Bytes.wrap(ownKey.publicKey())),
                sourceProof,
                new RosterTransitionWeights(hintsWeights, targetWeights),
                targetKeys,
                (delay, unit, executor) -> executor,
                Runnable::run,
                library,
                submissions,
                new WrapsMpcStateMachine());
        final var construction = HistoryProofConstruction.newBuilder()
                .constructionId(constructionId)
                .sourceRosterHash(Bytes.wrap(sourceProof == null ? "genesis" : "previous"))
                .targetRosterHash(Bytes.wrap(sourceProof == null ? "genesis" : "next"))
                .wrapsSigningState(new WrapsSigningState(R1, null))
                .build();
        assertSame(
                HistoryProver.Outcome.InProgress.INSTANCE,
                prover.advance(Instant.EPOCH, construction, targetKey, targetKeys, config, ledgerId, false));

        final var message = library.computeWrapsMessage(book(targetWeights, keys), targetKey.toByteArray());
        final var sortedSigners = signers.stream().sorted().toList();
        final var entropy =
                sortedSigners.stream().map(ignore -> randomBytes(32)).toList();
        final var r1 = IntStream.range(0, sortedSigners.size())
                .mapToObj(i -> library.runWrapsPhaseR1(
                        entropy.get(i), message, keys.get(sortedSigners.get(i)).privateKey()))
                .toArray(byte[][]::new);
        final var r2 = IntStream.range(0, sortedSigners.size())
                .mapToObj(i -> library.runWrapsPhaseR2(
                        entropy.get(i),
                        message,
                        r1,
                        keys.get(sortedSigners.get(i)).privateKey(),
                        sourceBook,
                        signers))
                .toArray(byte[][]::new);
        final var r3 = IntStream.range(0, sortedSigners.size())
                .mapToObj(i -> library.runWrapsPhaseR3(
                        entropy.get(i),
                        message,
                        r1,
                        r2,
                        keys.get(sortedSigners.get(i)).privateKey(),
                        sourceBook,
                        signers))
                .toArray(byte[][]::new);
        replay(prover, constructionId, R1, sortedSigners, r1);
        replay(prover, constructionId, R2, sortedSigners, r2);
        replay(prover, constructionId, R3, sortedSigners, r3);
        final var aggregate = construction
                .copyBuilder()
                .wrapsSigningState(new WrapsSigningState(AGGREGATE, null))
                .build();
        assertSame(
                HistoryProver.Outcome.InProgress.INSTANCE,
                prover.advance(Instant.EPOCH, aggregate, targetKey, targetKeys, config, ledgerId, true));
        final var captured = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions)
                .submitExplicitProofVote(org.mockito.ArgumentMatchers.eq(constructionId), captured.capture());
        assertNotNull(captured.getValue());
        return captured.getValue();
    }

    private static void replay(
            final WrapsHistoryProver prover,
            final long constructionId,
            final WrapsPhase phase,
            final List<Long> signers,
            final byte[][] messages) {
        for (int i = 0; i < signers.size(); i++) {
            assertNotNull(messages[i]);
            prover.replayWrapsSigningMessage(
                    constructionId,
                    new WrapsMessagePublication(signers.get(i), Bytes.wrap(messages[i]), phase, Instant.EPOCH));
        }
    }

    private static AddressBook book(final SortedMap<Long, Long> weights, final Map<Long, SchnorrKeys> keys) {
        return AddressBook.from(
                weights,
                id -> keys.containsKey(id)
                        ? keys.get(id).publicKey()
                        : HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray());
    }

    private static SortedMap<Long, Long> participatingWeights(
            final SortedMap<Long, Long> weights, final Set<Long> participants) {
        final var result = new TreeMap<Long, Long>();
        participants.forEach(id -> result.put(id, weights.get(id)));
        return result;
    }

    private static SortedMap<Long, Long> weights(final long... values) {
        final var weights = new TreeMap<Long, Long>();
        IntStream.range(0, values.length).forEach(i -> weights.put((long) i, values[i]));
        return weights;
    }

    private Bytes verificationKey() {
        return Bytes.wrap(randomBytes(1096));
    }

    private static HistoryProof roundTrip(final HistoryProof proof) throws ParseException {
        // Native uncompressed proofs exceed PBJ's default 2 MiB field limit.
        return HistoryProof.PROTOBUF.parse(
                HistoryProof.PROTOBUF.toBytes(proof).toReadableSequentialData(),
                false,
                false,
                Codec.DEFAULT_MAX_DEPTH,
                16 * 1024 * 1024);
    }

    private byte[] randomBytes(final int length) {
        final var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
