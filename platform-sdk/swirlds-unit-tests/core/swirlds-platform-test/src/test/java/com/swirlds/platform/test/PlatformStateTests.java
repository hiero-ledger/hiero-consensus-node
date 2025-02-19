/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
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

import static com.swirlds.platform.test.PlatformStateUtils.randomPlatformState;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.FAKE_MERKLE_STATE_LIFECYCLES;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.registerMerkleStateRootClassIds;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.state.PlatformState;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Platform State Tests")
class PlatformStateTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    public static void setup() throws FileNotFoundException, ConstructableRegistryException {
        new TestConfigBuilder().getOrCreateConfig();
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Test Copy")
    void testCopy() {

        final PlatformState platformState = new PlatformState();

        final PlatformState copy = platformState.copy();

        MerkleCryptoFactory.getInstance().digestTreeSync(platformState);
        MerkleCryptoFactory.getInstance().digestTreeSync(copy);

        assertNotSame(platformState, copy, "copy should not return the same object");
        assertEquals(platformState.getHash(), copy.getHash(), "copy should be equal to the original");
        assertFalse(platformState.isDestroyed(), "copy should not have been deleted");
        assertEquals(0, copy.getReservationCount(), "copy should have no references");
        assertSame(platformState.getRoute(), copy.getRoute(), "route should be recycled");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.IO)
    @DisplayName("Platform State Serialization Test")
    @SuppressWarnings("resource")
    void platformStateSerializationTest() throws IOException, ConstructableRegistryException {
        registerMerkleStateRootClassIds();
        final TestMerkleStateRoot root = new TestMerkleStateRoot();
        FAKE_MERKLE_STATE_LIFECYCLES.initPlatformState(root);

        final InputOutputStream io = new InputOutputStream();
        randomPlatformState(root, TEST_PLATFORM_STATE_FACADE);
        io.getOutput().writeMerkleTree(testDirectory, root);

        io.startReading();

        final TestMerkleStateRoot decodedState = io.getInput().readMerkleTree(testDirectory, Integer.MAX_VALUE);

        MerkleCryptoFactory.getInstance().digestTreeSync(root);
        MerkleCryptoFactory.getInstance().digestTreeSync(decodedState);

        assertEquals(root.getHash(), decodedState.getHash(), "expected deserialized object to be equal");
    }
}
