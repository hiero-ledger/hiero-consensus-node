// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_LABEL;
import static org.hiero.consensus.roster.RosterStateId.ROSTER_STATE_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTER_STATE_STATE_LABEL;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link WritableRosterStore} re-keys stored rosters to another digest type, and hashes new rosters with
 * the digest type of the stored roster hashes.
 */
class WritableRosterStoreRehashTest {
    private static final Roster CURRENT = rosterOf(0, 1, 2);
    private static final Roster PREVIOUS = rosterOf(0, 1);
    private static final Roster CANDIDATE = rosterOf(0, 1, 2, 3);

    private final Map<ProtoBytes, Roster> storedRosters = new HashMap<>();
    private final AtomicReference<RosterState> storedRosterState = new AtomicReference<>();
    private MapWritableStates states;

    @BeforeEach
    void setUp() {
        states = MapWritableStates.builder()
                .state(new MapWritableKVState<>(ROSTERS_STATE_ID, ROSTERS_STATE_LABEL, storedRosters))
                .state(new FunctionWritableSingletonState<>(
                        ROSTER_STATE_STATE_ID,
                        ROSTER_STATE_STATE_LABEL,
                        storedRosterState::get,
                        storedRosterState::set))
                .build();
    }

    @Test
    void movesActiveAndCandidateRostersToTheGivenDigest() {
        givenStoredWith(DigestType.SHA_384, CANDIDATE, CURRENT, PREVIOUS);

        rehash(DigestType.SHA_256);

        assertThat(storedRosters)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        keyOf(CURRENT, DigestType.SHA_256), CURRENT,
                        keyOf(PREVIOUS, DigestType.SHA_256), PREVIOUS,
                        keyOf(CANDIDATE, DigestType.SHA_256), CANDIDATE));
        assertThat(storedRosterState.get())
                .isEqualTo(rosterState(
                        hashOf(CANDIDATE, DigestType.SHA_256),
                        List.of(
                                new RoundRosterPair(20L, hashOf(CURRENT, DigestType.SHA_256)),
                                new RoundRosterPair(10L, hashOf(PREVIOUS, DigestType.SHA_256)))));
    }

    @Test
    void movesARosterThatIsBothCandidateAndActiveOnce() {
        givenStoredWith(DigestType.SHA_384, CURRENT, CURRENT, PREVIOUS);

        rehash(DigestType.SHA_256);

        assertThat(storedRosters)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        keyOf(CURRENT, DigestType.SHA_256), CURRENT,
                        keyOf(PREVIOUS, DigestType.SHA_256), PREVIOUS));
        assertThat(storedRosterState.get().candidateRosterHash()).isEqualTo(hashOf(CURRENT, DigestType.SHA_256));
        assertThat(storedRosterState.get().roundRosterPairs().getFirst().activeRosterHash())
                .isEqualTo(hashOf(CURRENT, DigestType.SHA_256));
    }

    @Test
    void keepsAnEmptyCandidateHashAndAHashWithNoStoredRoster() {
        final var danglingHash = hashOf(CANDIDATE, DigestType.SHA_384);
        storedRosters.put(keyOf(CURRENT, DigestType.SHA_384), CURRENT);
        storedRosterState.set(
                rosterState(danglingHash, List.of(new RoundRosterPair(20L, hashOf(CURRENT, DigestType.SHA_384)))));

        rehash(DigestType.SHA_256);

        assertThat(storedRosterState.get().candidateRosterHash()).isEqualTo(danglingHash);
        assertThat(storedRosters)
                .containsExactlyInAnyOrderEntriesOf(Map.of(keyOf(CURRENT, DigestType.SHA_256), CURRENT));
    }

    @Test
    void changesNothingWhenTheRostersAlreadyUseTheDigest() {
        givenStoredWith(DigestType.SHA_384, CANDIDATE, CURRENT, PREVIOUS);
        final var rostersBefore = Map.copyOf(storedRosters);
        final var rosterStateBefore = storedRosterState.get();

        rehash(DigestType.SHA_384);

        assertThat(storedRosters).containsExactlyInAnyOrderEntriesOf(rostersBefore);
        assertThat(storedRosterState.get()).isSameAs(rosterStateBefore);
    }

    @Test
    void movingBackRestoresTheOriginalKeys() {
        givenStoredWith(DigestType.SHA_384, CANDIDATE, CURRENT, PREVIOUS);
        final var rostersBefore = Map.copyOf(storedRosters);
        final var rosterStateBefore = storedRosterState.get();

        rehash(DigestType.SHA_256);
        rehash(DigestType.SHA_384);

        assertThat(storedRosters).containsExactlyInAnyOrderEntriesOf(rostersBefore);
        assertThat(storedRosterState.get()).isEqualTo(rosterStateBefore);
    }

    @Test
    void doesNothingWithoutARosterState() {
        rehash(DigestType.SHA_256);

        assertThat(storedRosterState.get()).isNull();
        assertThat(storedRosters).isEmpty();
    }

    @Test
    void puttingTheActiveRosterAgainIsANoOpAfterRehashing() {
        givenStoredWith(DigestType.SHA_384, null, CURRENT, PREVIOUS);
        rehash(DigestType.SHA_256);
        final var rosterStateAfterRehash = storedRosterState.get();

        new WritableRosterStore(states).putActiveRoster(CURRENT, 30L);
        states.commit();

        assertThat(storedRosterState.get()).isEqualTo(rosterStateAfterRehash);
        assertThat(storedRosters).hasSize(2);
    }

    @Test
    void newRostersFollowTheDigestOfTheStoredHashes() {
        givenStoredWith(DigestType.SHA_384, null, CURRENT);
        rehash(DigestType.SHA_256);

        final var store = new WritableRosterStore(states);
        store.putCandidateRoster(CANDIDATE);
        states.commit();

        assertThat(store.rosterHashOf(CANDIDATE)).isEqualTo(hashOf(CANDIDATE, DigestType.SHA_256));
        assertThat(storedRosterState.get().candidateRosterHash()).isEqualTo(hashOf(CANDIDATE, DigestType.SHA_256));
        assertThat(storedRosters).containsKey(keyOf(CANDIDATE, DigestType.SHA_256));
    }

    @Test
    void genesisRosterUsesTheDefaultDigest() {
        new WritableRosterStore(states, DigestType.SHA_256).putActiveRoster(CURRENT, 0L);
        states.commit();

        assertThat(storedRosters)
                .containsExactlyInAnyOrderEntriesOf(Map.of(keyOf(CURRENT, DigestType.SHA_256), CURRENT));
    }

    @Test
    void genesisRosterUsesThePlatformDefaultDigestByDefault() {
        new WritableRosterStore(states).putActiveRoster(CURRENT, 0L);
        states.commit();

        assertThat(storedRosters)
                .containsExactlyInAnyOrderEntriesOf(Map.of(keyOf(CURRENT, Cryptography.DEFAULT_DIGEST_TYPE), CURRENT));
    }

    @Test
    void usesTheCandidateDigestWhenNoRosterIsActive() {
        storedRosterState.set(rosterState(hashOf(CANDIDATE, DigestType.SHA_256), List.of()));

        assertThat(new ReadableRosterStoreImpl(states, DigestType.SHA_384).rosterHashOf(CURRENT))
                .isEqualTo(hashOf(CURRENT, DigestType.SHA_256));
    }

    @Test
    void usesTheDefaultDigestWhenNoRosterHashIsStored() {
        storedRosterState.set(RosterState.DEFAULT);

        assertThat(new ReadableRosterStoreImpl(states, DigestType.SHA_256).rosterHashOf(CURRENT))
                .isEqualTo(hashOf(CURRENT, DigestType.SHA_256));
        assertThat(new ReadableRosterStoreImpl(states, DigestType.SHA_384).rosterHashOf(CURRENT))
                .isEqualTo(hashOf(CURRENT, DigestType.SHA_384));
    }

    private void rehash(final DigestType digestType) {
        new WritableRosterStore(states).rehashRosters(digestType);
        states.commit();
    }

    /** Stores the rosters under their hashes with the given digest, the newest active roster first. */
    private void givenStoredWith(final DigestType digestType, final Roster candidate, final Roster... activeRosters) {
        final var candidateHash = candidate == null ? Bytes.EMPTY : hashOf(candidate, digestType);
        if (candidate != null) {
            storedRosters.put(keyOf(candidate, digestType), candidate);
        }
        final List<RoundRosterPair> pairs = new ArrayList<>();
        for (int i = 0; i < activeRosters.length; i++) {
            storedRosters.put(keyOf(activeRosters[i], digestType), activeRosters[i]);
            pairs.add(new RoundRosterPair(20L - 10L * i, hashOf(activeRosters[i], digestType)));
        }
        storedRosterState.set(rosterState(candidateHash, pairs));
    }

    private static RosterState rosterState(final Bytes candidateHash, final List<RoundRosterPair> pairs) {
        return RosterState.newBuilder()
                .candidateRosterHash(candidateHash)
                .roundRosterPairs(pairs)
                .build();
    }

    /** Hashes a roster directly, so the expectations don't depend on the code under test. */
    private static Bytes hashOf(final Roster roster, final DigestType digestType) {
        return Bytes.wrap(
                digestType.buildDigest().digest(Roster.PROTOBUF.toBytes(roster).toByteArray()));
    }

    private static ProtoBytes keyOf(final Roster roster, final DigestType digestType) {
        return new ProtoBytes(hashOf(roster, digestType));
    }

    private static Roster rosterOf(final long... nodeIds) {
        return new Roster(Arrays.stream(nodeIds)
                .mapToObj(nodeId -> RosterEntry.newBuilder()
                        .nodeId(nodeId)
                        .weight(10L)
                        .gossipCaCertificate(Bytes.wrap(new byte[] {1, 2, 3}))
                        .gossipEndpoint(ServiceEndpoint.newBuilder()
                                .domainName("node" + nodeId)
                                .port(50211)
                                .build())
                        .build())
                .toList());
    }
}
