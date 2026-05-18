// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RosterStateUtilsTest {
    @Test
    void previewsCandidateAdoptionWithoutChangingState() {
        final var previousRoster = rosterWithNodeIds(1, 2, 3);
        final var currentRoster = rosterWithNodeIds(4, 5, 6);
        final var candidateRoster = rosterWithNodeIds(7, 8, 9);
        final var currentRosterHash = RosterUtils.hash(currentRoster).getBytes();
        final var previousRosterHash = RosterUtils.hash(previousRoster).getBytes();
        final var candidateRosterHash = RosterUtils.hash(candidateRoster).getBytes();
        final var state = stateWithRosterState(
                new RosterState(
                        candidateRosterHash,
                        List.of(new RoundRosterPair(10, currentRosterHash), new RoundRosterPair(3, previousRosterHash)),
                        false),
                Map.of(
                        previousRosterHash, previousRoster,
                        currentRosterHash, currentRoster,
                        candidateRosterHash, candidateRoster));

        final var effectiveHistory =
                RosterStateUtils.createRosterHistoryWithCandidateAdoption(state, candidateRoster, 11);

        assertEquals(candidateRoster, effectiveHistory.getCurrentRoster());
        assertEquals(currentRoster, effectiveHistory.getPreviousRoster());
        assertEquals(candidateRoster, effectiveHistory.getRosterForRound(11));
        assertEquals(currentRoster, effectiveHistory.getRosterForRound(10));
        assertNull(effectiveHistory.getRosterForRound(9));
        assertEquals(currentRoster, RosterStateUtils.createRosterHistory(state).getCurrentRoster());
    }

    @Test
    void adoptionOfCurrentRosterReturnsPersistedHistory() {
        final var currentRoster = rosterWithNodeIds(1, 2, 3);
        final var currentRosterHash = RosterUtils.hash(currentRoster).getBytes();
        final var state = stateWithRosterState(
                new RosterState(currentRosterHash, List.of(new RoundRosterPair(10, currentRosterHash)), false),
                Map.of(currentRosterHash, currentRoster));

        final var effectiveHistory =
                RosterStateUtils.createRosterHistoryWithCandidateAdoption(state, currentRoster, 11);

        assertEquals(currentRoster, effectiveHistory.getCurrentRoster());
        assertEquals(currentRoster, effectiveHistory.getPreviousRoster());
        assertEquals(currentRoster, effectiveHistory.getRosterForRound(10));
        assertNull(effectiveHistory.getRosterForRound(9));
    }

    @SuppressWarnings("unchecked")
    private static State stateWithRosterState(final RosterState rosterState, final Map<Bytes, Roster> rostersByHash) {
        final var state = mock(State.class);
        final var readableStates = mock(ReadableStates.class);
        final ReadableKVState<ProtoBytes, Roster> rosters = mock(ReadableKVState.class);
        final ReadableSingletonState<RosterState> rosterStateSingleton = mock(ReadableSingletonState.class);
        when(state.getReadableStates(RosterStateId.SERVICE_NAME)).thenReturn(readableStates);
        when(readableStates.<ProtoBytes, Roster>get(RosterStateId.ROSTERS_STATE_ID))
                .thenReturn(rosters);
        when(readableStates.<RosterState>getSingleton(RosterStateId.ROSTER_STATE_STATE_ID))
                .thenReturn(rosterStateSingleton);
        when(rosterStateSingleton.get()).thenReturn(rosterState);
        rostersByHash.forEach((hash, roster) -> {
            final var key = new ProtoBytes(hash);
            when(rosters.get(key)).thenReturn(roster);
            when(rosters.contains(key)).thenReturn(true);
        });
        return state;
    }

    private static Roster rosterWithNodeIds(final long... nodeIds) {
        return Roster.newBuilder()
                .rosterEntries(Arrays.stream(nodeIds)
                        .mapToObj(id -> RosterEntry.newBuilder()
                                .nodeId(id)
                                .weight(1)
                                .gossipCaCertificate(Bytes.wrap("cert" + id))
                                .gossipEndpoint(ServiceEndpoint.newBuilder()
                                        .domainName("node" + id + ".test")
                                        .port(50211)
                                        .build())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
