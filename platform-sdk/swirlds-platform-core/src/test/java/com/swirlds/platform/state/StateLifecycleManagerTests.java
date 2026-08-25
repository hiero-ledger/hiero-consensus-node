// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.state.test.fixtures.merkle.TestStateUtils.destroyStateLifecycleManager;
import static org.hiero.base.file.FileUtils.rethrowIO;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.base.Reservable;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.utility.test.fixtures.file.TestFileSystemManager;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator;
import org.hiero.consensus.state.test.fixtures.TestingAppStateInitializer;
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
        final Roster roster = RosterFactory.randomRoster(Randotron.create(), 4);
        when(platform.getRoster()).thenReturn(roster);

        final Path defaultRootLocation = rethrowIO(() -> Files.createTempDirectory("testRootDir"));
        final FileSystemManager fileSystemManager = new TestFileSystemManager(defaultRootLocation);
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        stateLifecycleManager = new VirtualMapStateLifecycleManager(
                new NoOpMetrics(), Time.getCurrent(), configuration, fileSystemManager);

        // copy just to init immutableLastState
        initialState = stateLifecycleManager.copyMutableState();
        TestingAppStateInitializer.initPlatformState(initialState);

        setCreationSoftwareVersionTo(
                initialState,
                SemanticVersion.newBuilder().major(nextInt(1, 100)).build());
    }

    @AfterEach
    void tearDown() {
        if (!initialState.isDestroyed()) {
            initialState.release();
        }
        destroyStateLifecycleManager(stateLifecycleManager);
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
        stateLifecycleManager.initWithState(state1);

        assertEquals(
                2,
                state1.getRoot().getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in VirtualMapStateLifecycleManager.");
        final VirtualMapState consensusState1 = stateLifecycleManager.getMutableState();
        assertEquals(
                1,
                consensusState1.getRoot().getReservationCount(),
                "The current consensus state should have a single reference count.");

        final SignedState ss2 = newSignedState();
        final VirtualMapState state2 = ss2.getState();
        stateLifecycleManager.initWithState(state2);
        final VirtualMapState consensusState2 = stateLifecycleManager.getMutableState();

        assertEquals(
                2,
                state2.getRoot().getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in VirtualMapStateLifecycleManager.");
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
    @DisplayName("Preparing for a freeze disables background compaction")
    void prepareForFreezeDisablesBackgroundCompaction() {
        final VirtualMapStateLifecycleManager manager = spy((VirtualMapStateLifecycleManager) stateLifecycleManager);
        final VirtualMapState state = mock(VirtualMapState.class);
        final VirtualMap virtualMap = mock(VirtualMap.class);
        final VirtualDataSource dataSource = mock(VirtualDataSource.class);
        doReturn(state).when(manager).getMutableState();
        when(state.getRoot()).thenReturn(virtualMap);
        when(virtualMap.getDataSource()).thenReturn(dataSource);

        manager.prepareForFreeze();

        verify(dataSource).disableAndInterruptBackgroundCompaction();
    }

    private static SignedState newSignedState() {
        final SignedState ss = new RandomSignedStateGenerator().build();
        final Reservable state = ss.getState().getRoot();
        assertEquals(
                1, state.getReservationCount(), "Creating a signed state should increment the state reference count.");
        return ss;
    }
}
