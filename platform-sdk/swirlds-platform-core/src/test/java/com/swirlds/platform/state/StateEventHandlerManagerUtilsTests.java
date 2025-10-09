// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.metrics.StateMetrics;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.test.fixtures.merkle.TestVirtualMapState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class StateEventHandlerManagerUtilsTests {

    @Test
    void testFastCopyIsMutable() {
        final String virtualMapLabel =
                "vm-" + StateEventHandlerManagerUtilsTests.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
        final MerkleNodeState state = TestVirtualMapState.createInstanceWithVirtualMapLabel(virtualMapLabel);
        TestingAppStateInitializer.initPlatformState(state);
        state.getRoot().reserve();
        final StateMetrics stats = mock(StateMetrics.class);
        final State result = SwirldStateManagerUtils.fastCopy(
                state, stats, SemanticVersion.newBuilder().major(1).build(), TEST_PLATFORM_STATE_FACADE);

        assertFalse(result.isImmutable(), "The copy state should be mutable.");
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "Fast copy should not change the reference count of the state it copies.");
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "Fast copy should return a new state with a reference count of 1.");
        state.release();
        result.release();
    }

    @AfterEach
    void tearDown() {
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
