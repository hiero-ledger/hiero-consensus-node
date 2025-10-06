// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.state.TestVirtualMapState;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("State Test")
class StateTest {

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Test Copy")
    void testCopy() {

        final MerkleNodeState state = randomSignedState(false).getState();
        final MerkleNodeState copy = state.copy();

        assertNotSame(state, copy, "copy should not return the same object");
        assertEquals(state.getHash(), copy.getHash(), "copy should be equal to the original");
        assertFalse(state.isDestroyed(), "copy should not have been deleted");
        assertEquals(0, copy.getRoot().getReservationCount(), "copy should have no references");
        assertSame(state.getRoot().getRoute(), copy.getRoot().getRoute(), "route should be recycled");
        state.release();
        copy.release();
    }

    /**
     * Verify behavior when something tries to reserve a state.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Try Reserve")
    void tryReserveTest() {
        final MerkleNodeState state = randomSignedState(true).getState();
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "A state referenced only by a signed state should have a ref count of 1");

        assertTrue(state.getRoot().tryReserve(), "tryReserve() should succeed because the state is not destroyed.");
        assertEquals(2, state.getRoot().getReservationCount(), "tryReserve() should increment the reference count.");

        state.release();
        state.release();

        assertTrue(state.isDestroyed(), "state should be destroyed when fully released.");
        assertFalse(state.getRoot().tryReserve(), "tryReserve() should fail when the state is destroyed");
    }

    // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19905
    private static SignedState randomSignedState(boolean isSupposedToBeHashed) {
        Random random = new Random(0);
        final String virtualMapLabel = "vm-" + StateTest.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
        final MerkleNodeState merkleNodeState = TestVirtualMapState.createInstanceWithVirtualMapLabel(virtualMapLabel);
        boolean shouldSaveToDisk = random.nextBoolean();
        SignedState signedState = new SignedState(
                TestPlatformContextBuilder.create().build().getConfiguration(),
                CryptoStatic::verifySignature,
                merkleNodeState,
                "test",
                shouldSaveToDisk,
                false,
                false,
                new PlatformStateFacade());
        if (isSupposedToBeHashed) {
            // Hash the underlying VirtualMap
            signedState.getState().getHash();
        }

        return signedState;
    }

    @AfterEach
    void tearDown() {
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
