// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.Reservable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.test.fixtures.merkle.TestVirtualMapState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateLifecycleManagerTests {

    private StateLifecycleManager stateLifecycleManager;
    private MerkleNodeState initialState;

    @BeforeEach
    void setup() {
        final SwirldsPlatform platform = mock(SwirldsPlatform.class);
        final Roster roster = RandomRosterBuilder.create(Randotron.create()).build();
        when(platform.getRoster()).thenReturn(roster);
        PlatformStateFacade platformStateFacade = new PlatformStateFacade();
        initialState = newState(platformStateFacade);
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        stateLifecycleManager = new StateLifecycleManagerImpl(
                platformContext.getMetrics(), platformContext.getTime(), TestVirtualMapState::new);
        stateLifecycleManager.initState(initialState, true);
    }

    @AfterEach
    void tearDown() {
        if (!initialState.isDestroyed()) {
            initialState.release();
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
        final Reservable state1 = ss1.getState().getRoot();
        stateLifecycleManager.initState(ss1.getState(), false);

        assertEquals(
                2,
                state1.getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in StateLifecycleManagerImpl.");
        final MerkleNodeState consensusState1 = stateLifecycleManager.getMutableState();
        assertEquals(
                1,
                consensusState1.getRoot().getReservationCount(),
                "The current consensus state should have a single reference count.");

        final SignedState ss2 = newSignedState();
        stateLifecycleManager.initState(ss2.getState(), false);
        final MerkleNodeState consensusState2 = stateLifecycleManager.getMutableState();

        Reservable state2 = ss2.getState().getRoot();
        assertEquals(
                2,
                state2.getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in StateLifecycleManagerImpl.");
        assertEquals(
                1,
                consensusState2.getRoot().getReservationCount(),
                "The current consensus state should have a single reference count.");
        assertEquals(
                1,
                state1.getReservationCount(),
                "The previous immutable state was replaced, so the old state's reference count should have been "
                        + "decremented.");
        state1.release();
        state2.release();
        state2.release();
        consensusState2.release();
    }

    private static MerkleNodeState newState(PlatformStateFacade platformStateFacade) {
        final String virtualMapLabel =
                StateLifecycleManagerTests.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
        final MerkleNodeState state = TestVirtualMapState.createInstanceWithVirtualMapLabel(virtualMapLabel);
        TestingAppStateInitializer.initPlatformState(state);

        platformStateFacade.setCreationSoftwareVersionTo(
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
