// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @deprecated This test class is only required for the testing of MerkleStateRoot class and will be removed together with that class.
 */
@Deprecated
@DisplayName("State Registry Tests")
class StateRegistryTests {

    private static SemanticVersion version;
    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    static void setUp() {
        version = SemanticVersion.newBuilder().major(nextInt(1, 100)).build();
        registerMerkleStateRootClassIds();
    }

    @AfterAll
    static void tearDown() {
        // Don't leave the registry with a strange configuration after these tests
        RuntimeObjectRegistry.reset();
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Active State Count Test")
    void activeStateCountTest() throws IOException {

        // Restore the registry to its original condition at boot time
        RuntimeObjectRegistry.reset();

        assertEquals(
                0,
                RuntimeObjectRegistry.getActiveObjectsCount(TestMerkleStateRoot.class),
                "no states have been created yet");

        final List<TestMerkleStateRoot> states = new LinkedList<>();
        // Create a bunch of states
        for (int i = 0; i < 100; i++) {
            states.add(new TestMerkleStateRoot());
            assertEquals(
                    states.size(),
                    RuntimeObjectRegistry.getActiveObjectsCount(TestMerkleStateRoot.class),
                    "actual count should match expected count");
        }

        // Fast copy a state
        final TestMerkleStateRoot stateToCopy = new TestMerkleStateRoot();
        states.add(stateToCopy);
        final TestMerkleStateRoot copyOfStateToCopy = stateToCopy.copy();
        states.add(copyOfStateToCopy);
        assertEquals(
                states.size(),
                RuntimeObjectRegistry.getActiveObjectsCount(TestMerkleStateRoot.class),
                "actual count should match expected count");

        final Path dir = testDirectory;

        // Deserialize a state
        final TestMerkleStateRoot stateToSerialize = new TestMerkleStateRoot();
        final TestPlatformStateFacade platformStateFacade = new TestPlatformStateFacade();
        TestingAppStateInitializer.DEFAULT.initPlatformState(stateToSerialize);
        final var platformState = platformStateFacade.getWritablePlatformStateOf(stateToSerialize);
        platformState.bulkUpdate(v -> {
            v.setCreationSoftwareVersion(version);
            v.setLegacyRunningEventHash(new Hash());
        });

        states.add(stateToSerialize);
        final InputOutputStream io = new InputOutputStream();
        io.getOutput().writeMerkleTree(dir, stateToSerialize);
        io.startReading();
        final TestMerkleStateRoot deserializedState = io.getInput().readMerkleTree(CONFIGURATION, dir, 5);
        states.add(deserializedState);
        assertEquals(
                states.size(),
                RuntimeObjectRegistry.getActiveObjectsCount(TestMerkleStateRoot.class),
                "actual count should match expected count");

        // Deleting states in a random order should cause the number of states to decrease
        final Random random = new Random();
        while (!states.isEmpty()) {
            states.remove(random.nextInt(states.size())).release();
            assertEquals(
                    states.size(),
                    RuntimeObjectRegistry.getActiveObjectsCount(TestMerkleStateRoot.class),
                    "actual count should match expected count");
        }
    }
}
