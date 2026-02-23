// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.scratchpad;

import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Scratchpad Tests")
class ScratchpadTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    private Configuration configuration;

    private final NodeId selfId = NodeId.of(0);

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        Files.createDirectories(testDirectory);
        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(testDirectory.resolve("tmp"));
        this.configuration = new TestConfigBuilder()
                .withValue(StateCommonConfig_.SAVED_STATE_DIRECTORY, testDirectory.toString())
                .getOrCreateConfig();
    }

    @AfterEach
    void afterEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
    }

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero");
    }

    @Test
    @DisplayName("Basic Behavior Test")
    void basicBehaviorTest() {
        final Random random = getRandomPrintSeed();

        final Scratchpad<TestScratchpadType> scratchpad =
                Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");
        scratchpad.logContents();

        final Path scratchpadDirectory =
                testDirectory.resolve("scratchpad").resolve("0").resolve("test");

        // No scratchpad file will exist until we write the first value
        assertFalse(scratchpadDirectory.toFile().exists());

        // Values are null by default
        assertNull(scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        // Write a value for the first time

        final Hash hash1 = randomHash(random);

        assertNull(scratchpad.set(TestScratchpadType.FOO, hash1));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash1, scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        final SerializableLong long1 = new SerializableLong(random.nextLong());
        assertNull(scratchpad.set(TestScratchpadType.BAR, long1));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash1, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long1, scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        final NodeId nodeId1 = NodeId.of(random.nextInt(0, 1000));
        assertNull(scratchpad.set(TestScratchpadType.BAZ, nodeId1));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash1, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long1, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId1, scratchpad.get(TestScratchpadType.BAZ));

        // Overwrite an existing value

        final Hash hash2 = randomHash(random);
        assertEquals(hash1, scratchpad.set(TestScratchpadType.FOO, hash2));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash2, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long1, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId1, scratchpad.get(TestScratchpadType.BAZ));

        final SerializableLong long2 = new SerializableLong(random.nextLong());
        assertEquals(long1, scratchpad.set(TestScratchpadType.BAR, long2));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash2, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long2, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId1, scratchpad.get(TestScratchpadType.BAZ));

        final NodeId nodeId2 = NodeId.of(random.nextInt(1001, 2000));
        assertEquals(nodeId1, scratchpad.set(TestScratchpadType.BAZ, nodeId2));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash2, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long2, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId2, scratchpad.get(TestScratchpadType.BAZ));

        // Clear the scratchpad

        scratchpad.clear();
        assertNull(scratchpadDirectory.toFile().listFiles());
        assertNull(scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        // Write after a clear

        final Hash hash3 = randomHash(random);
        assertNull(scratchpad.set(TestScratchpadType.FOO, hash3));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash3, scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        final SerializableLong long3 = new SerializableLong(random.nextLong());
        assertNull(scratchpad.set(TestScratchpadType.BAR, long3));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash3, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long3, scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        final NodeId nodeId3 = NodeId.of(random.nextInt(2001, 3000));
        assertNull(scratchpad.set(TestScratchpadType.BAZ, nodeId3));
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash3, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long3, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId3, scratchpad.get(TestScratchpadType.BAZ));

        // Simulate a restart
        final Scratchpad<TestScratchpadType> scratcphad2 =
                Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");
        scratchpad.logContents();

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
        assertEquals(hash3, scratcphad2.get(TestScratchpadType.FOO));
        assertEquals(long3, scratcphad2.get(TestScratchpadType.BAR));
        assertEquals(nodeId3, scratcphad2.get(TestScratchpadType.BAZ));
    }

    /**
     * This test simulates a crash between the copy of the next scratchpad file and the deletion of the previous
     * scratchpad file.
     */
    @Test
    @DisplayName("Multiple Files Test")
    void multipleFilesTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final Scratchpad<TestScratchpadType> scratchpad =
                Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");
        scratchpad.logContents();

        final Path scratchpadDirectory =
                testDirectory.resolve("scratchpad").resolve("0").resolve("test");

        // No scratchpad file will exist until we write the first value
        assertFalse(Files.exists(scratchpadDirectory));

        // Values are null by default
        assertNull(scratchpad.get(TestScratchpadType.FOO));

        final Hash hash1 = randomHash(random);
        scratchpad.set(TestScratchpadType.FOO, hash1);
        assertEquals(hash1, scratchpad.get(TestScratchpadType.FOO));

        // After a write, there should always be exactly one scratchpad file
        final File[] files = scratchpadDirectory.toFile().listFiles();
        assertEquals(1, files.length);

        // Make a copy of that file
        final Path scratchpadFile = files[0].toPath();
        final Path copyPath = testDirectory.resolve(scratchpadFile.getFileName());
        Files.copy(scratchpadFile, copyPath);

        final Hash hash2 = randomHash(random);
        scratchpad.set(TestScratchpadType.FOO, hash2);

        // After a write, there should always be exactly one scratchpad file
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        // Copy the file back, simulating a crash
        Files.copy(copyPath, scratchpadFile);

        // Simulate a restart
        final Scratchpad scratchpad2 = Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");

        assertEquals(hash2, scratchpad2.get(TestScratchpadType.FOO));

        // The extra file should have been cleaned up on restart
        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);
    }

    @Test
    @DisplayName("Atomic Operation Test")
    void atomicOperationTest() {
        final Random random = getRandomPrintSeed();

        final Scratchpad<TestScratchpadType> scratchpad =
                Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");
        scratchpad.logContents();

        final Path scratchpadDirectory =
                testDirectory.resolve("scratchpad").resolve("0").resolve("test");

        // No scratchpad file will exist until we write the first value
        assertFalse(scratchpadDirectory.toFile().exists());

        // Values are null by default
        assertNull(scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        // Write a value for the first time

        final Hash hash1 = randomHash(random);
        final SerializableLong long1 = new SerializableLong(random.nextLong());
        final NodeId nodeId1 = NodeId.of(random.nextInt(0, 1000));

        scratchpad.atomicOperation(map -> {
            assertNull(map.put(TestScratchpadType.FOO, hash1));
            assertNull(map.put(TestScratchpadType.BAR, long1));
            assertNull(map.put(TestScratchpadType.BAZ, nodeId1));
        });

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        assertEquals(hash1, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long1, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId1, scratchpad.get(TestScratchpadType.BAZ));

        // Overwrite an existing value

        final Hash hash2 = randomHash(random);
        final SerializableLong long2 = new SerializableLong(random.nextLong());
        final NodeId nodeId2 = NodeId.of(random.nextInt(1001, 2000));

        scratchpad.atomicOperation(map -> {
            assertEquals(hash1, map.put(TestScratchpadType.FOO, hash2));
            assertEquals(long1, map.put(TestScratchpadType.BAR, long2));
            assertEquals(nodeId1, map.put(TestScratchpadType.BAZ, nodeId2));
        });

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        assertEquals(hash2, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long2, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId2, scratchpad.get(TestScratchpadType.BAZ));

        // Clear the scratchpad

        scratchpad.clear();
        assertNull(scratchpadDirectory.toFile().listFiles());

        scratchpad.atomicOperation(map -> {
            assertNull(scratchpad.get(TestScratchpadType.FOO));
            assertNull(scratchpad.get(TestScratchpadType.BAR));
            assertNull(scratchpad.get(TestScratchpadType.BAZ));
        });

        assertNull(scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        // Write after a clear

        final Hash hash3 = randomHash(random);
        final SerializableLong long3 = new SerializableLong(random.nextLong());
        final NodeId nodeId3 = NodeId.of(random.nextInt(2001, 3000));

        scratchpad.atomicOperation(map -> {
            assertNull(map.put(TestScratchpadType.FOO, hash3));
            assertNull(map.put(TestScratchpadType.BAR, long3));
            assertNull(map.put(TestScratchpadType.BAZ, nodeId3));
        });

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        assertEquals(hash3, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long3, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId3, scratchpad.get(TestScratchpadType.BAZ));

        // Simulate a restart
        final Scratchpad<TestScratchpadType> scratcphad2 =
                Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");
        scratchpad.logContents();

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        scratcphad2.atomicOperation(map -> {
            assertEquals(hash3, map.get(TestScratchpadType.FOO));
            assertEquals(long3, map.get(TestScratchpadType.BAR));
            assertEquals(nodeId3, map.get(TestScratchpadType.BAZ));
        });

        assertEquals(hash3, scratcphad2.get(TestScratchpadType.FOO));
        assertEquals(long3, scratcphad2.get(TestScratchpadType.BAR));
        assertEquals(nodeId3, scratcphad2.get(TestScratchpadType.BAZ));
    }

    @Test
    void optionalAtomicOperationTest() {
        final Random random = getRandomPrintSeed();

        final Scratchpad<TestScratchpadType> scratchpad =
                Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");
        scratchpad.logContents();

        final Path scratchpadDirectory =
                testDirectory.resolve("scratchpad").resolve("0").resolve("test");

        // No scratchpad file will exist until we write the first value
        assertFalse(scratchpadDirectory.toFile().exists());

        // Values are null by default
        assertNull(scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        // Write a value for the first time

        final Hash hash1 = randomHash(random);
        final SerializableLong long1 = new SerializableLong(random.nextLong());
        final NodeId nodeId1 = NodeId.of(random.nextInt(0, 1000));

        scratchpad.atomicOperation(map -> {
            assertNull(map.put(TestScratchpadType.FOO, hash1));
            assertNull(map.put(TestScratchpadType.BAR, long1));
            assertNull(map.put(TestScratchpadType.BAZ, nodeId1));

            return true;
        });

        // Should have no effect.
        scratchpad.atomicOperation(map -> false);

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        assertEquals(hash1, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long1, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId1, scratchpad.get(TestScratchpadType.BAZ));

        // Overwrite an existing value

        final Hash hash2 = randomHash(random);
        final SerializableLong long2 = new SerializableLong(random.nextLong());
        final NodeId nodeId2 = NodeId.of(random.nextInt(1001, 2000));

        scratchpad.atomicOperation(map -> {
            assertEquals(hash1, map.put(TestScratchpadType.FOO, hash2));
            assertEquals(long1, map.put(TestScratchpadType.BAR, long2));
            assertEquals(nodeId1, map.put(TestScratchpadType.BAZ, nodeId2));

            return true;
        });

        // Should have no effect.
        scratchpad.atomicOperation(map -> false);

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        assertEquals(hash2, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long2, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId2, scratchpad.get(TestScratchpadType.BAZ));

        // Clear the scratchpad

        scratchpad.clear();
        assertNull(scratchpadDirectory.toFile().listFiles());

        scratchpad.atomicOperation(map -> {
            assertNull(scratchpad.get(TestScratchpadType.FOO));
            assertNull(scratchpad.get(TestScratchpadType.BAR));
            assertNull(scratchpad.get(TestScratchpadType.BAZ));

            return true;
        });

        // Should have no effect.
        scratchpad.atomicOperation(map -> false);

        assertNull(scratchpad.get(TestScratchpadType.FOO));
        assertNull(scratchpad.get(TestScratchpadType.BAR));
        assertNull(scratchpad.get(TestScratchpadType.BAZ));

        // Write after a clear

        final Hash hash3 = randomHash(random);
        final SerializableLong long3 = new SerializableLong(random.nextLong());
        final NodeId nodeId3 = NodeId.of(random.nextInt(2001, 3000));

        scratchpad.atomicOperation(map -> {
            assertNull(map.put(TestScratchpadType.FOO, hash3));
            assertNull(map.put(TestScratchpadType.BAR, long3));
            assertNull(map.put(TestScratchpadType.BAZ, nodeId3));

            return true;
        });

        // Should have no effect.
        scratchpad.atomicOperation(map -> false);

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        assertEquals(hash3, scratchpad.get(TestScratchpadType.FOO));
        assertEquals(long3, scratchpad.get(TestScratchpadType.BAR));
        assertEquals(nodeId3, scratchpad.get(TestScratchpadType.BAZ));

        // Simulate a restart
        final Scratchpad<TestScratchpadType> scratcphad2 =
                Scratchpad.create(configuration, selfId, TestScratchpadType.class, "test");
        scratchpad.logContents();

        assertEquals(1, scratchpadDirectory.toFile().listFiles().length);

        scratcphad2.atomicOperation(map -> {
            assertEquals(hash3, map.get(TestScratchpadType.FOO));
            assertEquals(long3, map.get(TestScratchpadType.BAR));
            assertEquals(nodeId3, map.get(TestScratchpadType.BAZ));

            return true;
        });

        // Should have no effect.
        scratchpad.atomicOperation(map -> false);

        assertEquals(hash3, scratcphad2.get(TestScratchpadType.FOO));
        assertEquals(long3, scratcphad2.get(TestScratchpadType.BAR));
        assertEquals(nodeId3, scratcphad2.get(TestScratchpadType.BAZ));

        // Should have no effect.
        scratchpad.atomicOperation(map -> false);

        assertEquals(hash3, scratcphad2.get(TestScratchpadType.FOO));
        assertEquals(long3, scratcphad2.get(TestScratchpadType.BAR));
        assertEquals(nodeId3, scratcphad2.get(TestScratchpadType.BAZ));
    }

    @Test
    @DisplayName("Illegal Scratchpad Id Test")
    void illegalScratchpadIdTest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Scratchpad.create(configuration, selfId, TestScratchpadType.class, ""));

        assertThrows(
                IllegalArgumentException.class,
                () -> Scratchpad.create(configuration, selfId, TestScratchpadType.class, "foobar/baz"));

        assertThrows(
                IllegalArgumentException.class,
                () -> Scratchpad.create(configuration, selfId, TestScratchpadType.class, "foobar\\baz"));

        assertThrows(
                IllegalArgumentException.class,
                () -> Scratchpad.create(configuration, selfId, TestScratchpadType.class, "foobar\"baz"));

        assertThrows(
                IllegalArgumentException.class,
                () -> Scratchpad.create(configuration, selfId, TestScratchpadType.class, "foobar*baz"));

        // should not throw
        Scratchpad.create(configuration, selfId, TestScratchpadType.class, "foo.bar_baz-1234");
    }
}
