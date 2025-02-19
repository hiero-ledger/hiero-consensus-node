// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.cryptography.asciiarmored.AsciiArmoredFiles;
import com.hedera.cryptography.bls.BlsKeyPair;
import com.hedera.cryptography.bls.BlsPrivateKey;
import com.hedera.cryptography.bls.BlsPublicKey;
import com.hedera.cryptography.bls.GroupAssignment;
import com.hedera.cryptography.bls.SignatureSchema;
import com.hedera.cryptography.pairings.api.Curve;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyPairSequenceManagerTest {
    private static final SignatureSchema SIGNATURE_SCHEMA =
            SignatureSchema.create(Curve.ALT_BN128, GroupAssignment.SHORT_SIGNATURES);
    private static final String PRIVATE_KEY_FILE_NAME = "hinTS.bls";

    @TempDir
    private Path tempDir;

    private KeyPairSequenceManager<BlsPrivateKey, BlsPublicKey, BlsKeyPair> subject;

    @BeforeEach
    void setUp() {
        subject = new KeyPairSequenceManager<>(
                tempDir,
                PRIVATE_KEY_FILE_NAME,
                KeyPairSequenceManagerTest::newPrivateKey,
                AsciiArmoredFiles::readPrivateKey,
                (privateKey, path) -> AsciiArmoredFiles.writeKey(path, privateKey),
                BlsPrivateKey::createPublicKey,
                BlsKeyPair::new);
    }

    @Test
    void createsDirectoryAndKeyPairIfNoneExist() {
        // when
        final var keyPair = subject.getOrCreateKeyPairFor(5);

        // then
        final var constructedDir = tempDir.resolve("5");
        assertTrue(Files.isDirectory(constructedDir), "Directory for ID=5 should be created");
        final var privateKeyFile = constructedDir.resolve(PRIVATE_KEY_FILE_NAME);
        assertTrue(Files.exists(privateKeyFile), "Private key file should be created");
        // The returned pair should be valid
        assertNotNull(keyPair);
        assertNotNull(keyPair.privateKey());
        assertNotNull(keyPair.publicKey());
    }

    @Test
    void reusesDirectoryForSameConstructionId() {
        // when
        final var firstPair = subject.getOrCreateKeyPairFor(5);
        final var secondPair = subject.getOrCreateKeyPairFor(5);

        // then
        assertEquals(
                firstPair.privateKey(),
                secondPair.privateKey(),
                "Should return the same private key for repeated calls at the same ID");
        assertEquals(
                firstPair.publicKey(),
                secondPair.publicKey(),
                "Should return the same public key for repeated calls at the same ID");
    }

    @Test
    void usesLargestDirectoryNotExceedingId() {
        // given two calls to create directories "2" and "5"
        final var pair2 = subject.getOrCreateKeyPairFor(2);
        final var pair5 = subject.getOrCreateKeyPairFor(5);

        // when
        final var pairForId3 = subject.getOrCreateKeyPairFor(3);
        final var pairForId5Again = subject.getOrCreateKeyPairFor(5);
        final var pairForId10 = subject.getOrCreateKeyPairFor(10);

        // then
        // For ID=3, the largest existing directory <= 3 is "2"
        assertEquals(
                pair2.privateKey(), pairForId3.privateKey(), "Should reuse the directory for ID=2 when asked for ID=3");
        // For ID=5, we exactly match "5"
        assertEquals(
                pair5.privateKey(),
                pairForId5Again.privateKey(),
                "Should reuse the directory for ID=5 when asked for ID=5 again");
        // For ID=10, the largest existing directory <= 10 is still "5"
        // so we do not create a "10" directory
        assertEquals(
                pair5.privateKey(),
                pairForId10.privateKey(),
                "Should reuse the directory for ID=5 when asked for ID=10");
        final var potentialDir10 = tempDir.resolve("10");
        assertFalse(Files.exists(potentialDir10), "Should not create directory 10");
    }

    @Test
    void purgeKeyPairsForConstructionsBeforeRemovesCorrectDirs() {
        subject.createKeyPairFor(1);
        subject.createKeyPairFor(5);
        subject.createKeyPairFor(10);

        assertTrue(Files.isDirectory(tempDir.resolve("1")));
        assertTrue(Files.isDirectory(tempDir.resolve("5")));
        assertTrue(Files.isDirectory(tempDir.resolve("10")));

        subject.purgeKeyPairsBefore(5);

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
        KeyPairSequenceManager.rm(originalDir);

        // now try to get the key pair again
        assertDoesNotThrow(
                () -> subject.getOrCreateKeyPairFor(7), "Should handle re-creating base directory if it was removed");
        final var newDir = originalDir.resolve("7");
        assertTrue(Files.isDirectory(newDir), "Directory should be re-created");
    }

    private static BlsPrivateKey newPrivateKey() {
        try {
            return BlsKeyPair.generate(SIGNATURE_SCHEMA).privateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
