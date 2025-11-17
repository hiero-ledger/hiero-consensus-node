// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.hiero.consensus.roster.WritableRosterStore.MAXIMUM_ROSTER_HISTORY_SIZE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RosterState.Builder;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.merkle.VirtualMapUtils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link WritableRosterStore} class.
 */
class WritableRosterStoreTest {

    private final WritableStates writableStates = mock(WritableStates.class);
    private WritableRosterStore writableRosterStore;
    private ReadableRosterStore readableRosterStore;

    @BeforeEach
    void setUp() {
        final String virtualMapLabel =
                "vm-" + WritableRosterStoreTest.class.getSimpleName() + java.util.UUID.randomUUID();
        final var virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel, 1);

        final WritableKVState<ProtoBytes, Roster> rosters = MapWritableKVState.<ProtoBytes, Roster>builder(
                        RosterStateId.ROSTERS_STATE_ID, RosterStateId.ROSTERS_STATE_LABEL)
                .build();
        when(writableStates.<ProtoBytes, Roster>get(RosterStateId.ROSTERS_STATE_ID))
                .thenReturn(rosters);
        when(writableStates.<RosterState>getSingleton(RosterStateId.ROSTER_STATE_STATE_ID))
                .thenReturn(new OnDiskWritableSingletonState<>(
                        RosterStateId.ROSTER_STATE_STATE_ID,
                        RosterStateId.ROSTER_STATE_STATE_LABEL,
                        RosterState.PROTOBUF,
                        virtualMap));

        readableRosterStore = new ReadableRosterStoreImpl(writableStates);
        writableRosterStore = new WritableRosterStore(writableStates);
    }

    @Test
    void testGetReturnsCorrectRoster() {
        final Roster expectedRoster = createValidTestRoster(1);
        writableRosterStore.putCandidateRoster(expectedRoster);
        final Bytes rosterHash = RosterUtils.hash(expectedRoster).getBytes();

        final Roster actualRoster = readableRosterStore.get(rosterHash);

        assertEquals(expectedRoster, actualRoster, "The returned roster should match the expected roster");
    }

    @Test
    void testGetReturnsNullForInvalidHash() {
        final Roster expectedRoster = createValidTestRoster(1);
        writableRosterStore.putCandidateRoster(expectedRoster);
        final Bytes rosterHash = Bytes.EMPTY;

        final Roster actualRoster = readableRosterStore.get(rosterHash);

        assertNull(actualRoster, "The returned roster should be null for an invalid hash");
    }

    @Test
    void testSetCandidateRosterReturnsSame() {
        final Roster roster1 = createValidTestRoster(1);
        writableRosterStore.putCandidateRoster(roster1);
        assertEquals(
                readableRosterStore.getCandidateRoster(),
                roster1,
                "Candidate roster should be the same as the one set");

        final Roster roster2 = createValidTestRoster(2);
        writableRosterStore.putCandidateRoster(roster2);
        assertEquals(roster2, readableRosterStore.getCandidateRoster(), "Candidate roster should be roster2");
    }

    @Test
    void testInvalidRosterThrowsException() {
        assertThrows(NullPointerException.class, () -> writableRosterStore.putCandidateRoster(null));
        assertThrows(InvalidRosterException.class, () -> writableRosterStore.putCandidateRoster(Roster.DEFAULT));
        assertThrows(InvalidRosterException.class, () -> writableRosterStore.putActiveRoster(Roster.DEFAULT, 1));
    }

    @Test
    void testInvalidRoundNumberThrowsException() {
        writableRosterStore.putActiveRoster(createValidTestRoster(2), 1);
        final Roster roster = createValidTestRoster(1);
        assertThrows(IllegalArgumentException.class, () -> writableRosterStore.putActiveRoster(roster, 0));
        assertThrows(IllegalArgumentException.class, () -> writableRosterStore.putActiveRoster(roster, -1));
    }

    /**
     * Tests that setting an active roster returns the active roster when getActiveRoster is called.
     */
    @Test
    void testGetCandidateRosterWithValidCandidateRoster() {
        final Roster activeRoster = createValidTestRoster(1);
        assertNull(readableRosterStore.getActiveRoster(), "Active roster should be null initially");
        writableRosterStore.putActiveRoster(activeRoster, 2);
        assertSame(
                readableRosterStore.getActiveRoster(),
                activeRoster,
                "Returned active roster should be the same as the one set");
    }

    @Test
    void testSetActiveRosterRemovesExistingCandidateRoster() {
        final Roster activeRoster = createValidTestRoster(1);
        final Roster candidateRoster = createValidTestRoster(2);
        writableRosterStore.putCandidateRoster(candidateRoster);
        assertSame(
                readableRosterStore.getCandidateRoster(),
                candidateRoster,
                "Candidate roster should be the same as one we've just set");
        writableRosterStore.putActiveRoster(activeRoster, 1);
        assertSame(
                readableRosterStore.getActiveRoster(),
                activeRoster,
                "Returned active roster should be the same as we've just set");
        assertNull(
                readableRosterStore.getCandidateRoster(),
                "No candidate roster should exist in the state immediately after setting a new active roster");
    }

    /**
     * Test that the oldest roster is removed when a third roster is set
     */
    @Test
    @DisplayName("Test Oldest Active Roster Cleanup")
    void testOldestActiveRosterRemoved() throws NoSuchFieldException, IllegalAccessException {
        final Roster roster1 = createValidTestRoster(3);
        writableRosterStore.putActiveRoster(roster1, 1);
        assertSame(readableRosterStore.getActiveRoster(), roster1, "Returned active roster should be roster1");

        final Roster roster2 = createValidTestRoster(1);
        writableRosterStore.putActiveRoster(roster2, 2);
        assertSame(readableRosterStore.getActiveRoster(), roster2, "Returned active roster should be roster2");

        // set a 3rd candidate roster and adopt it
        final Roster roster3 = createValidTestRoster(2);
        writableRosterStore.putActiveRoster(roster3, 3);
        final WritableSingletonState<RosterState> rosterState = getRosterState();
        assertEquals(
                2,
                Objects.requireNonNull(rosterState.get()).roundRosterPairs().size(),
                "Only 2 round roster pairs should exist");
        assertFalse(
                Objects.requireNonNull(rosterState.get())
                        .roundRosterPairs()
                        .contains(
                                new RoundRosterPair(2, RosterUtils.hash(roster1).getBytes())),
                "Oldest roster should be removed");
    }

    /**
     * Test that an exception is thrown if stored active rosters are ever > MAXIMUM_ROSTER_HISTORY_SIZE
     */
    @Test
    @DisplayName("Test Max Roster List Size Exceeded")
    void testMaximumRostersMoreThan2ThrowsException() throws NoSuchFieldException, IllegalAccessException {
        final List<RoundRosterPair> activeRosters = new ArrayList<>();
        activeRosters.add(new RoundRosterPair(
                1, RosterUtils.hash(createValidTestRoster(1)).getBytes()));
        activeRosters.add(new RoundRosterPair(
                2, RosterUtils.hash(createValidTestRoster(2)).getBytes()));
        activeRosters.add(new RoundRosterPair(
                3, RosterUtils.hash(createValidTestRoster(3)).getBytes()));

        final Builder rosterStateBuilder =
                RosterState.newBuilder().candidateRosterHash(Bytes.EMPTY).roundRosterPairs(activeRosters);
        final WritableSingletonState<RosterState> rosterState = getRosterState();
        rosterState.put(rosterStateBuilder.build());

        final Roster roster = createValidTestRoster(4);
        final Exception exception =
                assertThrows(IllegalStateException.class, () -> writableRosterStore.putActiveRoster(roster, 4));
        assertEquals(
                "Active rosters in the Roster state cannot be more than  " + MAXIMUM_ROSTER_HISTORY_SIZE,
                exception.getMessage());
    }

    /**
     * Test that when a roster hash collision occurs between a newly set active roster and another active roster in
     * history, the other roster isn't removed from the state when remove is called
     */
    @Test
    @DisplayName("Duplicate Roster Hash")
    void testRosterHashCollisions() {
        final Roster roster1 = createValidTestRoster(3);
        writableRosterStore.putActiveRoster(roster1, 1);
        assertSame(
                readableRosterStore.getActiveRoster(),
                roster1,
                "Returned active roster should be the same as the one set");

        final Roster roster2 = createValidTestRoster(1);
        writableRosterStore.putActiveRoster(roster2, 2);
        assertSame(
                readableRosterStore.getActiveRoster(),
                roster2,
                "Returned active roster should be the same as the one set");

        writableRosterStore.putActiveRoster(roster1, 3);
        assertSame(
                readableRosterStore.getActiveRoster(),
                roster1,
                "3rd active roster with hash collision with first returns the first roster");
    }

    /**
     * Tests that setting three active rosters in a row will be reflected in the roster history. The roster history
     * will contain two round roster pairs.
     */
    @Test
    @DisplayName("Test Roster History")
    void testRosterHistory() {
        final Roster roster1 = createValidTestRoster(3);
        writableRosterStore.putActiveRoster(roster1, 1);
        assertSame(
                readableRosterStore.getActiveRoster(),
                roster1,
                "Returned active roster should be the same as the one set");

        final Roster roster2 = createValidTestRoster(1);
        writableRosterStore.putActiveRoster(roster2, 2);
        assertSame(
                readableRosterStore.getActiveRoster(),
                roster2,
                "Returned active roster should be the same as the one set");

        final Roster roster3 = createValidTestRoster(2);
        writableRosterStore.putActiveRoster(roster3, 3);
        assertSame(
                readableRosterStore.getActiveRoster(),
                roster3,
                "Returned active roster should be the same as the one set");

        final List<RoundRosterPair> rosterHistory = readableRosterStore.getRosterHistory();
        assertEquals(2, rosterHistory.size(), "Roster history should contain 2 entries");

        final Bytes roster2Hash = RosterUtils.hash(roster2).getBytes();
        final Bytes roster3Hash = RosterUtils.hash(roster3).getBytes();

        assertTrue(
                rosterHistory.contains(new RoundRosterPair(2, roster2Hash)),
                "Roster history should contain the second roster");
        assertTrue(
                rosterHistory.contains(new RoundRosterPair(3, roster3Hash)),
                "Roster history should contain the third roster");
        assertFalse(
                rosterHistory.contains(
                        new RoundRosterPair(1, RosterUtils.hash(roster1).getBytes())),
                "Roster history should not contain the first roster");
    }

    @Test
    void testDoesNotSetSameRoster() {
        final Roster roster = createValidTestRoster(1);

        // First set the active roster (and remember its hash)
        writableRosterStore.putActiveRoster(roster, 1);
        assertNull(readableRosterStore.getPreviousRosterHash());
        assertEquals(roster, readableRosterStore.getActiveRoster());
        final Bytes rosterHash = readableRosterStore.getCurrentRosterHash();

        // Now set the same roster as active, but for the next round. Given that the active roster AND this roster are
        // the same, it will not set the roster
        writableRosterStore.putActiveRoster(roster, 2);

        final List<RoundRosterPair> history = readableRosterStore.getRosterHistory();
        assertEquals(1, history.size());
        assertEquals(rosterHash, history.getFirst().activeRosterHash());
        assertEquals(rosterHash, history.getLast().activeRosterHash());
    }

    @Test
    void testSetSameActiveRosterAndRoundSilentlyIgnores() {
        final Roster roster = createValidTestRoster(1);

        writableRosterStore.putActiveRoster(roster, 1);
        assertEquals(roster, readableRosterStore.getActiveRoster());

        assertDoesNotThrow(() -> writableRosterStore.putActiveRoster(roster, 1));
        assertEquals(writableRosterStore.getActiveRoster(), roster);
    }

    /**
     * Creates a valid test roster with the given number of entries.
     *
     * @param entries the number of entries
     * @return a valid roster
     */
    private Roster createValidTestRoster(final int entries) {
        final List<RosterEntry> entriesList = new LinkedList<>();
        for (int i = 0; i < entries; i++) {
            entriesList.add(RosterEntry.newBuilder()
                    .nodeId(i)
                    .weight(i + 1) // weight must be > 0
                    .gossipCaCertificate(Bytes.wrap("test" + i))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName("domain.com" + i)
                            .port(666)
                            .build())
                    .build());
        }
        return Roster.newBuilder().rosterEntries(entriesList).build();
    }

    /**
     * Gets the roster state from the WritableRosterStore via reflection for testing purposes only.
     *
     * @return the roster state
     * @throws NoSuchFieldException   if the field is not found
     * @throws IllegalAccessException if the field is not accessible
     */
    private WritableSingletonState<RosterState> getRosterState() throws NoSuchFieldException, IllegalAccessException {
        final Field field = WritableRosterStore.class.getDeclaredField("rosterState");
        field.setAccessible(true);
        return (WritableSingletonState<RosterState>) field.get(writableRosterStore);
    }
}
