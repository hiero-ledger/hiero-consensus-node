// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.Reservable;
import com.swirlds.common.constructable.ConstructableRegistration;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import com.swirlds.virtualmap.VirtualMap;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateLifecycleManagerTests {

    private StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private VirtualMapState initialState;

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        ConstructableRegistration.registerCoreConstructables();
    }

    @BeforeEach
    void setup() {
        final SwirldsPlatform platform = mock(SwirldsPlatform.class);
        final Roster roster = RandomRosterBuilder.create(Randotron.create()).build();
        when(platform.getRoster()).thenReturn(roster);
        initialState = newState();
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        stateLifecycleManager = new StateLifecycleManagerImpl(
                platformContext.getMetrics(),
                platformContext.getTime(),
                VirtualMapStateTestUtils::createTestStateWithVM,
                platformContext.getConfiguration());
        stateLifecycleManager.initState(initialState);
    }

    @AfterEach
    void tearDown() {
        if (!initialState.isDestroyed()) {
            initialState.release();
        }
        final VirtualMapState latestImmutable = stateLifecycleManager.getLatestImmutableState();
        if (latestImmutable != null && latestImmutable != initialState && !latestImmutable.isDestroyed()) {
            latestImmutable.release();
        }
        if (!stateLifecycleManager.getMutableState().isDestroyed()) {
            stateLifecycleManager.getMutableState().release();
        }
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @Test
    @DisplayName("Initial State - state reference counts")
    void initialStateReferenceCount() {
        assertEquals(
                1,
                initialState.getRoot().getReservationCount(),
                "The initial state is copied and should be referenced once as the previous immutable state.");
        Reservable consensusStateAsReservable =
                stateLifecycleManager.getMutableState().getRoot();
        assertEquals(
                1, consensusStateAsReservable.getReservationCount(), "The consensus state should have one reference.");
    }

    @Test
    @DisplayName("Load From Signed State - state reference counts")
    void initStateRefCount() {
        final SignedState ss1 = newSignedState();
        final VirtualMapState state1 = ss1.getState();
        stateLifecycleManager.initStateOnReconnect(state1);

        assertEquals(
                2,
                state1.getRoot().getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in StateLifecycleManagerImpl.");
        final VirtualMapState consensusState1 = stateLifecycleManager.getMutableState();
        assertEquals(
                1,
                consensusState1.getRoot().getReservationCount(),
                "The current consensus state should have a single reference count.");

        final SignedState ss2 = newSignedState();
        final VirtualMapState state2 = ss2.getState();
        stateLifecycleManager.initStateOnReconnect(state2);
        final VirtualMapState consensusState2 = stateLifecycleManager.getMutableState();

        assertEquals(
                2,
                state2.getRoot().getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in StateLifecycleManagerImpl.");
        assertEquals(
                1,
                consensusState2.getRoot().getReservationCount(),
                "The current consensus state should have a single reference count.");
        assertEquals(
                1,
                state1.getRoot().getReservationCount(),
                "The previous immutable state was replaced, so the old state's reference count should have been "
                        + "decremented.");
        state1.release();
        state2.release();
        state2.release();
        consensusState2.release();
    }

    @Test
    @DisplayName("copyMutableState() updates references and reservation counts")
    void copyMutableStateReferenceCounts() {
        final VirtualMapState beforeMutable = stateLifecycleManager.getMutableState();
        final VirtualMapState beforeImmutable = stateLifecycleManager.getLatestImmutableState();

        final VirtualMapState afterMutable = stateLifecycleManager.copyMutableState();
        final VirtualMapState newLatestImmutable = stateLifecycleManager.getLatestImmutableState();

        assertSame(beforeMutable, newLatestImmutable, "Previous mutable should become latest immutable");
        assertNotSame(beforeMutable, afterMutable, "A new mutable state instance should be created");

        assertEquals(1, afterMutable.getRoot().getReservationCount(), "Mutable state should have one reference");
        assertEquals(1, newLatestImmutable.getRoot().getReservationCount(), "Latest immutable should have one ref");
        assertEquals(-1, beforeImmutable.getRoot().getReservationCount(), "Old immutable should be released");
    }

    @Test
    @DisplayName("initState() rejects second startup initialization")
    void initStateRejectsSecondStartup() {
        final VirtualMapState another = newState();
        assertThrows(IllegalStateException.class, () -> stateLifecycleManager.initState(another));
        another.release();
    }

    @Test
    @DisplayName("initState() rejects immutable input state")
    void initStateRejectsImmutableInput() {
        final VirtualMapState immutable = stateLifecycleManager.getLatestImmutableState();
        assertThrows(MutabilityException.class, () -> stateLifecycleManager.initState(immutable));
    }

    @Test
    @DisplayName("getMutableState() throws if not initialized")
    void getMutableStateThrowsIfNotInitialized() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final StateLifecycleManager uninitialized = new StateLifecycleManagerImpl(
                platformContext.getMetrics(),
                platformContext.getTime(),
                VirtualMapStateTestUtils::createTestStateWithVM,
                platformContext.getConfiguration());
        assertThrows(IllegalStateException.class, uninitialized::getMutableState);
    }

    @Test
    @DisplayName("getLatestImmutableState() throws if not initialized")
    void getLatestImmutableStateThrowsIfNotInitialized() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final StateLifecycleManager uninitialized = new StateLifecycleManagerImpl(
                platformContext.getMetrics(),
                platformContext.getTime(),
                VirtualMapStateTestUtils::createTestStateWithVM,
                platformContext.getConfiguration());
        assertThrows(IllegalStateException.class, uninitialized::getLatestImmutableState);
    }

    @Test
    @DisplayName("createStateFrom() creates a state without changing reservation count and the state of the manager")
    void createStateFrom() {
        // Create an independent state and get its root (VirtualMap)
        final VirtualMapState state = VirtualMapStateTestUtils.createTestState();
        final VirtualMapState created = stateLifecycleManager.createStateFrom(state.getRoot());

        // The created state should be non-null and reference the same root
        assertSame(state.getRoot(), created.getRoot(), "createStateFrom should wrap the provided root");

        // Reservation count should remain unchanged by createStateFrom
        assertEquals(
                0,
                (created.getRoot()).getReservationCount(),
                "createStateFrom must not alter the root reservation count");

        // stateLifecycleManager remains unchanged
        assertNotSame(state, stateLifecycleManager.getLatestImmutableState());
        assertNotSame(state, stateLifecycleManager.getMutableState());

        state.release();
    }

    private static VirtualMapState newState() {
        final VirtualMapState state = VirtualMapStateTestUtils.createTestState();
        TestingAppStateInitializer.initPlatformState(state);

        setCreationSoftwareVersionTo(
                state, SemanticVersion.newBuilder().major(nextInt(1, 100)).build());

        assertEquals(0, state.getRoot().getReservationCount(), "A brand new state should have no references.");
        return state;
    }

    private static SignedState newSignedState() {
        final SignedState ss = new RandomSignedStateGenerator().build();
        final Reservable state = ss.getState().getRoot();
        assertEquals(
                1, state.getReservationCount(), "Creating a signed state should increment the state reference count.");
        return ss;
    }
}
