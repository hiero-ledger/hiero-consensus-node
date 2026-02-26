// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapUtils.CONFIGURATION;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import com.swirlds.virtualmap.VirtualMap;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class StateEventHandlerManagerUtilsTests {

    @Test
    void testFastCopyIsMutable() {
        final VirtualMapState state = VirtualMapStateTestUtils.createTestState();
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(new NoOpMetrics(), new FakeTime(), CONFIGURATION);
        TestingAppStateInitializer.initPlatformState(state);
        // Create a fast copy
        stateLifecycleManager.initWithState(state);

        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(1).build();

        final VirtualMapState copy = stateLifecycleManager.getMutableState();
        setCreationSoftwareVersionTo(copy, softwareVersion);

        assertFalse(copy.isImmutable(), "The copy state should be mutable.");
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "Fast copy should not change the reference count of the state it copies.");
        assertEquals(
                1,
                copy.getRoot().getReservationCount(),
                "Fast copy should return a new state with a reference count of 1.");
        state.release();
        copy.release();
    }

    @AfterEach
    void tearDown() {
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
