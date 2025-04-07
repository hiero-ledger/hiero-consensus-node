// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.cryptography.hints.HintsLibraryBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SequentialContentManagerTest {
    private static final String PRIVATE_KEY_FILE_NAME = "hinTS.bls";

    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final HintsLibraryBridge BRIDGE = HintsLibraryBridge.getInstance();

    @TempDir
    private Path tempDir;

    private SequentialContentManager<byte[]> subject;

    @BeforeEach
    void setUp() {
        subject = new SequentialContentManager<>(
                tempDir,
                "private key",
                PRIVATE_KEY_FILE_NAME,
                () -> {
                    final var bytes = new byte[32];
                    RANDOM.nextBytes(bytes);
                    return BRIDGE.generateSecretKey(bytes);
                },
                Files::readAllBytes,
                (bytes, p) -> Files.write(p, bytes));
    }

    @Test
    void createsDirectoryAndKeyPairIfNoneExist() {
        // when
        final var keyPair = subject.getOrCreateContent(5);

        // then
        final var constructedDir = tempDir.resolve("5");
        assertTrue(Files.isDirectory(constructedDir), "Directory for ID=5 should be created");
        final var privateKeyFile = constructedDir.resolve(PRIVATE_KEY_FILE_NAME);
        assertTrue(Files.exists(privateKeyFile), "Private key file should be created");
        // The returned pair should be valid
        assertNotNull(keyPair);
    }

    @Test
    void reusesDirectoryForSameConstructionId() {
        // when
        final var firstPair = subject.getOrCreateContent(5);
        final var secondPair = subject.getOrCreateContent(5);

        // then
        assertArrayEquals(firstPair, secondPair, "Should return the same content for repeated calls at the same ID");
        assertArrayEquals(firstPair, secondPair, "Should return the same content for repeated calls at the same ID");
    }

    @Test
    void usesLargestDirectoryNotExceedingId() {
        // given two calls to create directories "2" and "5"
        final var key2 = subject.getOrCreateContent(2);
        final var key5 = subject.getOrCreateContent(5);

        // when
        final var keyForId3 = subject.getOrCreateContent(3);
        final var keyForId5Again = subject.getOrCreateContent(5);
        final var keyForId10 = subject.getOrCreateContent(10);

        // then
        // For ID=3, the largest existing directory <= 3 is "2"
        assertArrayEquals(key2, keyForId3, "Should reuse the directory for ID=2 when asked for ID=3");
        // For ID=5, we exactly match "5"
        assertArrayEquals(key5, keyForId5Again, "Should reuse the directory for ID=5 when asked for ID=5 again");
        // For ID=10, the largest existing directory <= 10 is still "5"
        // so we do not create a "10" directory
        assertArrayEquals(key5, keyForId10, "Should reuse the directory for ID=5 when asked for ID=10");
        final var potentialDir10 = tempDir.resolve("10");
        assertFalse(Files.exists(potentialDir10), "Should not create directory 10");
    }

    @Test
    void purgeKeyPairsForConstructionsBeforeRemovesCorrectDirs() {
        subject.createContentFor(1);
        subject.createContentFor(5);
        subject.createContentFor(10);

        assertTrue(Files.isDirectory(tempDir.resolve("1")));
        assertTrue(Files.isDirectory(tempDir.resolve("5")));
        assertTrue(Files.isDirectory(tempDir.resolve("10")));

        subject.purgeContentBefore(5);

        assertFalse(Files.exists(tempDir.resolve("1")), "Dir '1' should be purged");
        assertTrue(Files.exists(tempDir.resolve("5")), "Dir '5' should remain");
        assertTrue(Files.exists(tempDir.resolve("10")), "Dir '10' should remain");
    }

    @Test
    void handlesNonExistentBaseDirectoryGracefully() {
        // If we manually remove the base directory before calling getOrCreateKeyPairFor
        // the code should handle it by re-creating it (or at least not crash).
        final var originalDir = tempDir;
        assertTrue(Files.isDirectory(originalDir));

        // remove it
        SequentialContentManager.rm(originalDir);

        // now try to get the key pair again
        assertDoesNotThrow(
                () -> subject.getOrCreateContent(7), "Should handle re-creating base directory if it was removed");
        final var newDir = originalDir.resolve("7");
        assertTrue(Files.isDirectory(newDir), "Directory should be re-created");
    }
}
