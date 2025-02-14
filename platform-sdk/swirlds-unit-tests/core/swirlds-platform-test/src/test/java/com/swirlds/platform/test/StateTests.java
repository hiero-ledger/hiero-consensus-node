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

import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.areTreesEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.state.BlockingState;
import com.swirlds.state.merkle.MerkleStateRoot;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("State Tests")
class StateTests {

    private static MerkleStateRoot merkleStateRoot;
    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        new TestConfigBuilder().getOrCreateConfig();

        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        merkleStateRoot = new BlockingState();

        merkleStateRoot.invalidateHash();
        MerkleCryptoFactory.getInstance().digestTreeSync(merkleStateRoot);
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.IO)
    @DisplayName("State Serialization Test")
    void stateSerializationTest() throws IOException {
        InputOutputStream io = new InputOutputStream();

        io.getOutput().writeMerkleTree(testDirectory, merkleStateRoot);

        io.startReading();

        final MerkleNodeState decodedState = io.getInput().readMerkleTree(testDirectory, Integer.MAX_VALUE);
        MerkleCryptoFactory.getInstance().digestTreeSync(decodedState);

        assertEquals(merkleStateRoot.getHash(), decodedState.getHash(), "expected trees to be equal");
        assertTrue(areTreesEqual(merkleStateRoot, decodedState), "expected trees to be equal");
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("State Copy Test")
    void stateCopyTest() {
        final MerkleStateRoot copiedState = merkleStateRoot.copy();
        MerkleCryptoFactory.getInstance().digestTreeSync(copiedState);

        assertEquals(merkleStateRoot.getHash(), copiedState.getHash(), "expected trees to be equal");
        assertTrue(areTreesEqual(merkleStateRoot, copiedState), "expected trees to be equal");
    }
}
