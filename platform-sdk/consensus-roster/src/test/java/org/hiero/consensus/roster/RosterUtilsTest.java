// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.test.fixtures.roster.RosterServiceStateMock;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableStates;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.Random;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.crypto.PreGeneratedX509Certs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class RosterUtilsTest {

    @Test
    void testHash() {
        final Hash hash = RosterUtils.hash(Roster.DEFAULT);
        assertEquals(
                "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b",
                hash.toString());

        final Hash anotherHash = RosterUtils.hash(
                Roster.DEFAULT.copyBuilder().rosterEntries(RosterEntry.DEFAULT).build());
        assertEquals(
                "5d693ce2c5d445194faee6054b4d8fe4a4adc1225cf0afc2ecd7866ea895a0093ea3037951b75ab7340b75699aa1db1d",
                anotherHash.toString());

        final Hash validRosterHash = RosterUtils.hash(RosterValidatorTests.buildValidRoster());
        assertEquals(
                "b58744d9cfbceda7b1b3c50f501c3ab30dc4ea7e59e96c8071a7bb2198e7071bde40535605c7f37db47e7a1efe5ef280",
                validRosterHash.toString());
    }

    @Test
    void testFetchHostname() {
        assertEquals(
                "domain.name",
                RosterUtils.fetchHostname(
                        RosterEntry.newBuilder()
                                .gossipEndpoint(List.of(ServiceEndpoint.newBuilder()
                                        .port(666)
                                        .domainName("domain.name")
                                        .build()))
                                .build(),
                        0));

        assertEquals(
                "domain.name.2",
                RosterUtils.fetchHostname(
                        RosterEntry.newBuilder()
                                .gossipEndpoint(List.of(
                                        ServiceEndpoint.newBuilder()
                                                .port(666)
                                                .domainName("domain.name")
                                                .build(),
                                        ServiceEndpoint.newBuilder()
                                                .port(666)
                                                .domainName("domain.name.2")
                                                .build()))
                                .build(),
                        1));

        assertEquals(
                "10.0.0.1",
                RosterUtils.fetchHostname(
                        RosterEntry.newBuilder()
                                .gossipEndpoint(List.of(ServiceEndpoint.newBuilder()
                                        .port(666)
                                        .ipAddressV4(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                                        // While the below violates the ServiceEndpoint specification,
                                        // there's no any hard validations present, and we want to ensure
                                        // the logic in the RosterUtils.fetchHostname() picks up the IP
                                        // instead of the domainName in this case, so we provide both in this test:
                                        .domainName("domain.name")
                                        .build()))
                                .build(),
                        0));

        assertThrows(
                IllegalArgumentException.class,
                () -> RosterUtils.fetchHostname(
                        RosterEntry.newBuilder()
                                .gossipEndpoint(List.of(ServiceEndpoint.newBuilder()
                                        .port(666)
                                        .ipAddressV4(Bytes.wrap(new byte[] {10, 0, 0, 1, 2}))
                                        // While the below violates the ServiceEndpoint specification,
                                        // there's no any hard validations present, and we want to ensure
                                        // the logic in the RosterUtils.fetchHostname() picks up the IP
                                        // instead of the domainName in this case, so we provide both in this test:
                                        .domainName("domain.name")
                                        .build()))
                                .build(),
                        0));
    }

    @Test
    void testFetchPort() {
        assertEquals(
                666,
                RosterUtils.fetchPort(
                        RosterEntry.newBuilder()
                                .gossipEndpoint(List.of(ServiceEndpoint.newBuilder()
                                        .port(666)
                                        .domainName("domain.name")
                                        .build()))
                                .build(),
                        0));

        assertEquals(
                777,
                RosterUtils.fetchPort(
                        RosterEntry.newBuilder()
                                .gossipEndpoint(List.of(
                                        ServiceEndpoint.newBuilder()
                                                .port(666)
                                                .domainName("domain.name")
                                                .build(),
                                        ServiceEndpoint.newBuilder()
                                                .port(777)
                                                .domainName("domain.name.2")
                                                .build()))
                                .build(),
                        1));

        assertEquals(
                888,
                RosterUtils.fetchPort(
                        RosterEntry.newBuilder()
                                .gossipEndpoint(List.of(ServiceEndpoint.newBuilder()
                                        .port(888)
                                        .ipAddressV4(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                                        .build()))
                                .build(),
                        0));
    }

    @Test
    void testCreateRosterHistory() {
        final Random random = new Random();
        final VirtualMapState state = Mockito.mock(VirtualMapState.class);
        final ReadableStates readableStates = Mockito.mock(ReadableStates.class);
        Mockito.when(state.getReadableStates(PlatformStateService.NAME)).thenReturn(readableStates);

        final Roster currentRoster =
                RandomRosterBuilder.create(random).withSize(4).build();
        final Roster previousRoster =
                RandomRosterBuilder.create(random).withSize(3).build();
        RosterServiceStateMock.setup(state, currentRoster, 16L, previousRoster);

        final RosterHistory rosterHistory = RosterStateUtils.createRosterHistory(state);
        assertEquals(previousRoster, rosterHistory.getPreviousRoster());
        assertEquals(currentRoster, rosterHistory.getCurrentRoster());
    }

    @Test
    void testCreateRosterHistoryVerifyRound() {
        final Random random = RandomUtils.getRandomPrintSeed();
        final VirtualMapState state = Mockito.mock(VirtualMapState.class);
        final Roster currentRoster =
                RandomRosterBuilder.create(random).withSize(4).build();
        final Roster previousRoster =
                RandomRosterBuilder.create(random).withSize(3).build();
        RosterServiceStateMock.setup(state, currentRoster, 16L, previousRoster);

        final RosterHistory rosterHistory = RosterStateUtils.createRosterHistory(state);
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
    void testCreateRosterHistoryNoActiveRosters() {
        final VirtualMapState state = Mockito.mock(VirtualMapState.class);
        Mockito.when(state.getReadableStates(RosterStateId.SERVICE_NAME)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> RosterStateUtils.createRosterHistory(state));
    }

    @Test
    void testCreateRosterHistoryNoRosters() {
        final VirtualMapState state = Mockito.mock(VirtualMapState.class);
        RosterServiceStateMock.setup(state, null, 16L, null);

        assertThrows(IllegalArgumentException.class, () -> RosterStateUtils.createRosterHistory(state));
    }

    @Test
    void testFetchingCertificates() throws CertificateEncodingException {
        // Positive Case
        Assertions.assertEquals(
                PreGeneratedX509Certs.getSigCert(0),
                RosterUtils.fetchGossipCaCertificate(RosterEntry.newBuilder()
                        .gossipCaCertificate(
                                Bytes.wrap(PreGeneratedX509Certs.getSigCert(0).getEncoded()))
                        .build()));
        // Negative Cases
        assertNull(RosterUtils.fetchGossipCaCertificate(
                RosterEntry.newBuilder().gossipCaCertificate(null).build()));
        assertNull(RosterUtils.fetchGossipCaCertificate(
                RosterEntry.newBuilder().gossipCaCertificate(Bytes.EMPTY).build()));
        assertNull(RosterUtils.fetchGossipCaCertificate(RosterEntry.newBuilder()
                .gossipCaCertificate(
                        Bytes.wrap(PreGeneratedX509Certs.createBadCertificate().getEncoded()))
                .build()));
    }
}
