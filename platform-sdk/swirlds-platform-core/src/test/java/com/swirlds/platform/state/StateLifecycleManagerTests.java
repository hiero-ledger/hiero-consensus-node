// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.platform.state.service.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
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
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateLifecycleManagerTests {

    private StateLifecycleManager stateLifecycleManager;
    private MerkleNodeState initialState;

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        final var registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("org.hiero");
        registry.registerConstructables("com.swirlds.platform");
        registry.registerConstructables("com.swirlds.state");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructables("com.swirlds.merkledb");
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
        final MerkleNodeState latestImmutable = stateLifecycleManager.getLatestImmutableState();
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
    @DisplayName("copyMutableState() updates references and reservation counts")
    void copyMutableStateReferenceCounts() {
        final MerkleNodeState beforeMutable = stateLifecycleManager.getMutableState();
        final MerkleNodeState beforeImmutable = stateLifecycleManager.getLatestImmutableState();

        final MerkleNodeState afterMutable = stateLifecycleManager.copyMutableState();
        final MerkleNodeState newLatestImmutable = stateLifecycleManager.getLatestImmutableState();

        assertSame(beforeMutable, newLatestImmutable, "Previous mutable should become latest immutable");
        assertNotSame(beforeMutable, afterMutable, "A new mutable state instance should be created");

        assertEquals(1, afterMutable.getRoot().getReservationCount(), "Mutable state should have one reference");
        assertEquals(1, newLatestImmutable.getRoot().getReservationCount(), "Latest immutable should have one ref");
        assertEquals(-1, beforeImmutable.getRoot().getReservationCount(), "Old immutable should be released");
    }

    @Test
    @DisplayName("initState() rejects second startup initialization")
    void initStateRejectsSecondStartup() {
        final MerkleNodeState another = newState();
        assertThrows(IllegalStateException.class, () -> stateLifecycleManager.initState(another));
        another.release();
    }

    @Test
    @DisplayName("initState() rejects immutable input state")
    void initStateRejectsImmutableInput() {
        final MerkleNodeState immutable = stateLifecycleManager.getLatestImmutableState();
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

    private static MerkleNodeState newState() {
        final String virtualMapLabel =
                StateLifecycleManagerTests.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
        final MerkleNodeState state = VirtualMapStateTestUtils.createTestState();
        TestingAppStateInitializer.initPlatformState(state);

        setCreationSoftwareVersionTo(
                state, SemanticVersion.newBuilder().major(nextInt(1, 100)).build());

        assertEquals(0, state.getRoot().getReservationCount(), "A brand new state should have no references.");
        return state;
    }
}
