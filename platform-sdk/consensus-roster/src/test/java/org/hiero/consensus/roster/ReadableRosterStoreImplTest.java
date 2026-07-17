// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableRosterStoreImplTest {
    @Mock
    private ReadableStates readableStates;

    @Mock
    private ReadableSingletonState<RosterState> rosterState;

    private final Map<ProtoBytes, Roster> rosterMap = new HashMap<>();

    private ReadableRosterStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(readableStates.<RosterState>getSingleton(RosterStateId.ROSTER_STATE_STATE_ID))
                .willReturn(rosterState);
        final ReadableKVState<ProtoBytes, Roster> rosterKVState =
                new MapReadableKVState<>(ROSTERS_STATE_ID, ROSTERS_STATE_LABEL, rosterMap);
        given(readableStates.<ProtoBytes, Roster>get(RosterStateId.ROSTERS_STATE_ID))
                .willReturn(rosterKVState);
        subject = new ReadableRosterStoreImpl(readableStates);
    }

    @Test
    void nullCandidateRosterCasesPass() {
        assertNull(subject.getCandidateRosterHash());
        given(rosterState.get()).willReturn(RosterState.DEFAULT);
        assertNull(subject.getCandidateRosterHash());
    }

    @Test
    void nonNullCandidateRosterIsReturned() {
        final var fakeHash = Bytes.wrap("PRETEND");
        given(rosterState.get())
                .willReturn(
                        RosterState.newBuilder().candidateRosterHash(fakeHash).build());
        assertEquals(fakeHash, subject.getCandidateRosterHash());
    }

    @Test
    void testCreateRosterHistory() {
        final Random random = new Random();
        final Roster currentRoster =
                RandomRosterBuilder.create(random).withSize(4).build();
        final Roster previousRoster =
                RandomRosterBuilder.create(random).withSize(3).build();

        setup(currentRoster, 16L, previousRoster);

        final RosterHistory rosterHistory = subject.getRosterHistory();
        assertEquals(previousRoster, rosterHistory.getPreviousRoster());
        assertEquals(currentRoster, rosterHistory.getCurrentRoster());
    }

    @Test
    void testCreateRosterHistoryVerifyRound() {
        final Random random = RandomUtils.getRandomPrintSeed();
        final Roster currentRoster =
                RandomRosterBuilder.create(random).withSize(4).build();
        final Roster previousRoster =
                RandomRosterBuilder.create(random).withSize(3).build();
        setup(currentRoster, 16L, previousRoster);

        final RosterHistory rosterHistory = subject.getRosterHistory();
        assertEquals(currentRoster, rosterHistory.getCurrentRoster());
        assertEquals(previousRoster, rosterHistory.getPreviousRoster());

        assertEquals(currentRoster, rosterHistory.getRosterForRound(16));
        assertEquals(currentRoster, rosterHistory.getRosterForRound(18));
        assertEquals(currentRoster, rosterHistory.getRosterForRound(100));
        assertEquals(currentRoster, rosterHistory.getRosterForRound(Integer.MAX_VALUE));
        assertEquals(previousRoster, rosterHistory.getRosterForRound(15));
        assertEquals(previousRoster, rosterHistory.getRosterForRound(0));
        assertNull(rosterHistory.getRosterForRound(-1));
    }

    @Test
    void testCreateRosterHistoryNoRosters() {
        assertThrows(NullPointerException.class, () -> subject.getRosterHistory());
    }

    private void setup(@NonNull final Roster currentRoster, final long round, @NonNull final Roster previousRoster) {
        final Bytes currentRosterHash = RosterUtils.hash(currentRoster).getBytes();
        final Bytes previousRosterHash = RosterUtils.hash(previousRoster).getBytes();

        rosterMap.put(new ProtoBytes(currentRosterHash), currentRoster);
        rosterMap.put(new ProtoBytes(previousRosterHash), previousRoster);

        final List<RoundRosterPair> roundRosterPairs = List.of(
                RoundRosterPair.newBuilder()
                        .activeRosterHash(currentRosterHash)
                        .roundNumber(round)
                        .build(),
                RoundRosterPair.newBuilder()
                        .activeRosterHash(previousRosterHash)
                        .roundNumber(0L)
                        .build());
        given(rosterState.get())
                .willReturn(RosterState.newBuilder()
                        .roundRosterPairs(roundRosterPairs)
                        .build());
    }
}
