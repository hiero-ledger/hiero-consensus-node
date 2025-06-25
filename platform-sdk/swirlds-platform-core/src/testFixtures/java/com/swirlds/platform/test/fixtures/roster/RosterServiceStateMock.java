// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.roster;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * A utility class to help set up mock states with given current/previous rosters.
 */
public final class RosterServiceStateMock {
    private RosterServiceStateMock() {}

    /**
     * A helper version of the setup() method that configures the currentRoster only
     * which becomes active at round zero. See the doc for the complete setup()
     * method below for more information.
     *
     * @param stateMock a mock of the State interface
     * @param currentRoster a Roster to be active since round zero
     */
    public static void setup(final State stateMock, final Roster currentRoster) {
        setup(stateMock, currentRoster, 0, null);
    }

    /**
     * Configures the provided State mock with the currentRoster starting at the given round,
     * and optionally with the previousRoster (if not null) starting at round zero.
     * <p>
     * This method properly configures the RosterService states to provide proper RosterHistory
     * and have the roster(s) in the RosterMap. It also configures the ConsensusSnapshot mock
     * in the PlatformState to return the given round number.
     * <p>
     * If the previousRoster is not null, then the given round number must be greater than zero
     * because it's assumed that the previousRoster is active since the round zero,
     * and the current roster must have a round number greater than that.
     * <p>
     * To support tests that verify the behavior at genesis when no roster history exists yet,
     * the currentRoster may also be null. Normally, the previousRoster would also be null
     * in this case (although the method won't prevent one from specifying a non-null value),
     * which would normally result in an empty roster history.
     *
     * @param stateMock a mock of the State interface
     * @param currentRoster a Roster to be currently active, may be null
     * @param round a round number since which the currentRoster is active
     * @param previousRoster an optional Roster to be the previousRoster, active since round zero
     */
    public static void setup(
            @NonNull final State stateMock,
            @Nullable final Roster currentRoster,
            final long round,
            @Nullable final Roster previousRoster) {
        final ReadableStates readableStates = mock(ReadableStates.class);
        when(stateMock.getReadableStates(RosterStateId.NAME)).thenReturn(readableStates);
        final ReadableKVState<ProtoBytes, Roster> rosterMap = mock(ReadableKVState.class);
        when(readableStates.<ProtoBytes, Roster>get(RosterStateId.ROSTER_KEY)).thenReturn(rosterMap);

        List<RoundRosterPair> roundRosterPairs = new ArrayList<>();

        if (currentRoster != null) {
            final Bytes rosterHash = RosterUtils.hash(currentRoster).getBytes();
            final ProtoBytes value = new ProtoBytes(rosterHash);
            when(rosterMap.get(eq(value))).thenReturn(currentRoster);
            when(rosterMap.contains(eq(value))).thenReturn(true);
            roundRosterPairs.add(new RoundRosterPair(round, rosterHash));
        }

        if (previousRoster != null) {
            if (round <= 0L) {
                throw new IllegalArgumentException(
                        "With a non-null previousRoster, the round number for the currentRoster must be greater than zero: previousRoster="
                                + Roster.JSON.toJSON(previousRoster));
            }
            final Bytes previousRosterHash = RosterUtils.hash(previousRoster).getBytes();
            final ProtoBytes value = new ProtoBytes(previousRosterHash);
            when(rosterMap.get(eq(value))).thenReturn(previousRoster);
            when(rosterMap.contains(eq(value))).thenReturn(true);
            roundRosterPairs.add(new RoundRosterPair(0, previousRosterHash));
        }

        final RosterState rosterState = new RosterState(Bytes.EMPTY, roundRosterPairs);
        final ReadableSingletonState<RosterState> rosterReadableState = mock(ReadableSingletonState.class);
        when(readableStates.<RosterState>getSingleton(RosterStateId.ROSTER_STATES_KEY))
                .thenReturn(rosterReadableState);
        when(rosterReadableState.get()).thenReturn(rosterState);

        final ReadableSingletonState<PlatformState> platformReadableState = mock(ReadableSingletonState.class);
        final PlatformState platformState = mock(PlatformState.class);
        when(stateMock.getReadableStates(PlatformStateService.NAME)).thenReturn(readableStates);
        when(readableStates.<PlatformState>getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_KEY))
                .thenReturn(platformReadableState);
        when(platformReadableState.get()).thenReturn(platformState);

        final ConsensusSnapshot consensusSnapshot = mock(ConsensusSnapshot.class);
        when(consensusSnapshot.round()).thenReturn(round);
        when(platformState.consensusSnapshot()).thenReturn(consensusSnapshot);
    }
}
