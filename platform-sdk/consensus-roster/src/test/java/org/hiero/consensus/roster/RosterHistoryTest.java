// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RosterHistoryTest {
    private static final Roster ROSTER_1 = RosterValidatorTests.buildValidRoster();
    private static final Roster ROSTER_2 = RosterValidatorTests.buildValidRoster()
            .copyBuilder()
            .rosterEntries(Stream.<RosterEntry>concat(
                            ROSTER_1.rosterEntries().stream(),
                            List.of(RosterEntry.newBuilder()
                                            .nodeId(73846583745L)
                                            .weight(87432653L)
                                            .gossipCaCertificate(Bytes.wrap("test349573845"))
                                            .gossipEndpoint(ServiceEndpoint.newBuilder()
                                                    .domainName("test298543.com")
                                                    .port(999)
                                                    .build())
                                            .build())
                                    .stream())
                    .toList())
            .build();

    private static final long ROUND_1 = 1097534987L;
    private static final long ROUND_2 = 2983745987L;

    /**
     * Build RosterHistory object(s) for tests.
     * @return stream of arguments
     */
    private static Stream<Arguments> provideArguments() {
        final Bytes hash1 = RosterUtils.hash(ROSTER_1).getBytes();
        final Bytes hash2 = RosterUtils.hash(ROSTER_2).getBytes();

        return Stream.of(Arguments.of(new RosterHistory(
                List.of(
                        RoundRosterPair.newBuilder()
                                .roundNumber(ROUND_2)
                                .activeRosterHash(hash2)
                                .build(),
                        RoundRosterPair.newBuilder()
                                .roundNumber(ROUND_1)
                                .activeRosterHash(hash1)
                                .build()),
                Map.of(
                        hash1, ROSTER_1,
                        hash2, ROSTER_2))));
    }

    @ParameterizedTest
    @MethodSource({"provideArguments"})
    void testGetCurrentRoster(final RosterHistory rosterHistory) {
        assertEquals(ROSTER_2, rosterHistory.getCurrentRoster());
    }

    @ParameterizedTest
    @MethodSource({"provideArguments"})
    void testGetPreviousRoster(final RosterHistory rosterHistory) {
        assertEquals(ROSTER_1, rosterHistory.getPreviousRoster());
    }

    @ParameterizedTest
    @MethodSource({"provideArguments"})
    void testGetRosterForRound(final RosterHistory rosterHistory) {
        assertEquals(ROSTER_2, rosterHistory.getRosterForRound(ROUND_2 + 1));
        assertEquals(ROSTER_2, rosterHistory.getRosterForRound(ROUND_2));

        assertEquals(ROSTER_1, rosterHistory.getRosterForRound(ROUND_2 - 1));

        assertEquals(ROSTER_1, rosterHistory.getRosterForRound(ROUND_1 + 1));
        assertEquals(ROSTER_1, rosterHistory.getRosterForRound(ROUND_1));

        assertEquals(null, rosterHistory.getRosterForRound(ROUND_1 - 1));
    }

    @Test
    void testConstructor() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RosterHistory(List.of(), Map.of()),
                "Roster history is empty");

        final IllegalArgumentException iae1 = assertThrows(
                IllegalArgumentException.class,
                () -> new RosterHistory(
                        List.of(RoundRosterPair.newBuilder()
                                .roundNumber(ROUND_2)
                                .activeRosterHash(RosterUtils.hash(ROSTER_2).getBytes())
                                .build()),
                        Map.of()));
        assertTrue(iae1.getMessage().startsWith("Roster history refers to roster hashes not found in the roster map"));
    }
}
