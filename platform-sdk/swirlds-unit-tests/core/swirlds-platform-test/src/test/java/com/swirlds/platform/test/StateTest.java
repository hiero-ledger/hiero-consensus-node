/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.PlatformMerkleStateRoot;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.BasicSoftwareVersion;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("State Test")
class StateTest {

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Test Copy")
    void testCopy() {

        final PlatformMerkleStateRoot state = randomSignedState().getState();
        final PlatformMerkleStateRoot copy = state.copy();

        assertNotSame(state, copy, "copy should not return the same object");

        state.invalidateHash();
        MerkleCryptoFactory.getInstance().digestTreeSync(state);
        MerkleCryptoFactory.getInstance().digestTreeSync(copy);

        assertEquals(state.getHash(), copy.getHash(), "copy should be equal to the original");
        assertFalse(state.isDestroyed(), "copy should not have been deleted");
        assertEquals(0, copy.getReservationCount(), "copy should have no references");
        assertSame(state.getRoute(), copy.getRoute(), "route should be recycled");
    }

    /**
     * Verify behavior when something tries to reserve a state.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Try Reserve")
    void tryReserveTest() {
        final PlatformMerkleStateRoot state = randomSignedState().getState();
        assertEquals(
                1,
                state.getReservationCount(),
                "A state referenced only by a signed state should have a ref count of 1");

        assertTrue(state.tryReserve(), "tryReserve() should succeed because the state is not destroyed.");
        assertEquals(2, state.getReservationCount(), "tryReserve() should increment the reference count.");

        state.release();
        state.release();

        assertTrue(state.isDestroyed(), "state should be destroyed when fully released.");
        assertFalse(state.tryReserve(), "tryReserve() should fail when the state is destroyed");
    }

    private static SignedState randomSignedState() {
        Random random = new Random(0);
        PlatformMerkleStateRoot merkleStateRoot =
                new PlatformMerkleStateRoot(version -> new BasicSoftwareVersion(version.major()));
        boolean shouldSaveToDisk = random.nextBoolean();
        SignedState signedState = new SignedState(
                TestPlatformContextBuilder.create().build().getConfiguration(),
                CryptoStatic::verifySignature,
                merkleStateRoot,
                "test",
                shouldSaveToDisk,
                false,
                false,
                new PlatformStateFacade(version -> new BasicSoftwareVersion(version.major())));
        signedState.getState().setHash(RandomUtils.randomHash(random));
        return signedState;
    }
}
