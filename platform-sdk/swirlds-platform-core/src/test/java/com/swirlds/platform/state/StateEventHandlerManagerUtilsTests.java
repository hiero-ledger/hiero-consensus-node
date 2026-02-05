// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class StateEventHandlerManagerUtilsTests {

    @Test
    void testFastCopyIsMutable() {
        final VirtualMapState state = VirtualMapStateTestUtils.createTestState();
        TestingAppStateInitializer.initPlatformState(state);
        state.getRoot().reserve();

        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(1).build();
        // Create a fast copy
        final VirtualMapState copy = state.copy();
        setCreationSoftwareVersionTo(copy, softwareVersion);
        // Increment the reference count because this reference becomes the new value
        copy.getRoot().reserve();

        assertFalse(copy.isImmutable(), "The copy state should be mutable.");
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "Fast copy should not change the reference count of the state it copies.");
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "Fast copy should return a new state with a reference count of 1.");
        state.release();
        copy.release();
    }

    @AfterEach
    void tearDown() {
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
