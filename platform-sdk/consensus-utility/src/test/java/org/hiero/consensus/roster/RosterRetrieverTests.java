// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class RosterRetrieverTests {

    private static final Bytes HASH_555 = Bytes.wrap("555");
    private static final Bytes HASH_666 = Bytes.wrap("666");
    private static final Bytes HASH_777 = Bytes.wrap("777");

    private static final Roster ROSTER_555 = mock(Roster.class);
    private static final Roster ROSTER_666 = mock(Roster.class);
    private static final Roster ROSTER_777 = mock(Roster.class);

    @Mock
    private State state;

    @Mock
    private ReadableStates platfromReadableStates;

    @Mock
    private ReadableSingletonState<PlatformState> readablePlatformState;

    @Mock
    private PlatformState platformState;

    @Mock
    private ConsensusSnapshot consensusSnapshot;

    @Mock
    private ReadableStates rosterReadableStates;

    @Mock
    private ReadableSingletonState<RosterState> readableRosterState;

    @Mock
    private RosterState rosterState;

    @Mock
    private ReadableKVState<ProtoBytes, Roster> rosterMap;

    @BeforeEach
    public void setup() {
        // Mock all the happy cases at once.  Use lenient() so that Mockito allows unused stubbing.
        lenient().doReturn(platfromReadableStates).when(state).getReadableStates("PlatformStateService");
        lenient().doReturn(readablePlatformState).when(platfromReadableStates).getSingleton("PLATFORM_STATE");
        lenient().doReturn(platformState).when(readablePlatformState).get();
        lenient().doReturn(consensusSnapshot).when(platformState).consensusSnapshot();
        lenient().doReturn(666L).when(consensusSnapshot).round();
        lenient().doReturn(rosterReadableStates).when(state).getReadableStates("RosterService");
        lenient().doReturn(readableRosterState).when(rosterReadableStates).getSingleton("ROSTER_STATE");
        lenient().doReturn(rosterState).when(readableRosterState).get();
        lenient()
                .doReturn(List.of(
                        RoundRosterPair.newBuilder()
                                .roundNumber(777L)
                                .activeRosterHash(HASH_777)
                                .build(),
                        RoundRosterPair.newBuilder()
                                .roundNumber(666L)
                                .activeRosterHash(HASH_666)
                                .build(),
                        RoundRosterPair.newBuilder()
                                .roundNumber(555L)
                                .activeRosterHash(HASH_555)
                                .build()))
                .when(rosterState)
                .roundRosterPairs();
        lenient().doReturn(rosterMap).when(rosterReadableStates).get("ROSTERS");
        lenient()
                .doReturn(ROSTER_555)
                .when(rosterMap)
                .get(eq(ProtoBytes.newBuilder().value(HASH_555).build()));
        lenient()
                .doReturn(ROSTER_666)
                .when(rosterMap)
                .get(eq(ProtoBytes.newBuilder().value(HASH_666).build()));
        lenient()
                .doReturn(ROSTER_777)
                .when(rosterMap)
                .get(eq(ProtoBytes.newBuilder().value(HASH_777).build()));
    }

    @Test
    void testGetRound() {
        assertEquals(666L, TEST_PLATFORM_STATE_FACADE.roundOf(state));
    }

    private static Stream<Arguments> provideArgumentsForGetActiveRosterHash() {
        return Stream.of(
                Arguments.of(554L, null),
                Arguments.of(555L, HASH_555),
                Arguments.of(556L, HASH_555),
                Arguments.of(665L, HASH_555),
                Arguments.of(666L, HASH_666),
                Arguments.of(667L, HASH_666),
                Arguments.of(776L, HASH_666),
                Arguments.of(777L, HASH_777),
                Arguments.of(778L, HASH_777));
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForGetActiveRosterHash")
    void testGetActiveRosterHashForRound(final long round, final Bytes activeRosterHash) {
        assertEquals(activeRosterHash, RosterRetriever.getActiveRosterHash(state, round));
    }

    @Test
    void testRetrieveActiveOrGenesisActiveRoster() {
        assertEquals(ROSTER_666, RosterRetriever.retrieveActive(state, TEST_PLATFORM_STATE_FACADE.roundOf(state)));
    }

    private static Stream<Arguments> provideArgumentsForRetrieveActiveOrGenesisActiveParametrizedRoster() {
        return Stream.of(
                Arguments.of(554L, (Roster) null),
                Arguments.of(555L, ROSTER_555),
                Arguments.of(556L, ROSTER_555),
                Arguments.of(665L, ROSTER_555),
                Arguments.of(666L, ROSTER_666),
                Arguments.of(667L, ROSTER_666),
                Arguments.of(776L, ROSTER_666),
                Arguments.of(777L, ROSTER_777),
                Arguments.of(778L, ROSTER_777));
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForRetrieveActiveOrGenesisActiveParametrizedRoster")
    void testRetrieveActiveOrGenesisActiveParametrizedRoster(final long round, final Roster roster) {
        doReturn(round).when(consensusSnapshot).round();
        assertEquals(roster, RosterRetriever.retrieveActive(state, TEST_PLATFORM_STATE_FACADE.roundOf(state)));
    }

    private static Stream<Arguments> provideArgumentsForRetrieveActiveOrGenesisActiveForRoundRoster() {
        return Stream.of(
                Arguments.of(554L, null),
                Arguments.of(555L, ROSTER_555),
                Arguments.of(556L, ROSTER_555),
                Arguments.of(665L, ROSTER_555),
                Arguments.of(666L, ROSTER_666),
                Arguments.of(667L, ROSTER_666),
                Arguments.of(776L, ROSTER_666),
                Arguments.of(777L, ROSTER_777),
                Arguments.of(778L, ROSTER_777));
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForRetrieveActiveOrGenesisActiveForRoundRoster")
    void testRetrieveActiveOrGenesisActiveForRoundRoster(final long round, final Roster roster) {
        assertEquals(roster, RosterRetriever.retrieveActive(state, round));
    }

    @Test
    void testRetrieveActiveOrGenesisActiveAddressBookRoster() {
        // First try a very old round for which there's not a roster
        doReturn(554L).when(consensusSnapshot).round();
        assertEquals(null, RosterRetriever.retrieveActive(state, TEST_PLATFORM_STATE_FACADE.roundOf(state)));

        // Then try a newer round, but remove the roster from the RosterMap
        doReturn(666L).when(consensusSnapshot).round();
        doReturn(null)
                .when(rosterMap)
                .get(eq(ProtoBytes.newBuilder().value(HASH_666).build()));
        assertEquals(null, RosterRetriever.retrieveActive(state, TEST_PLATFORM_STATE_FACADE.roundOf(state)));
    }

    private static Bytes getCertBytes(X509Certificate certificate) {
        try {
            return Bytes.wrap(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
