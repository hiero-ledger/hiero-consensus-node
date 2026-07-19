// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TssKeyFilesTest {
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    @TempDir
    private Path tempDir;

    @Test
    void readsLatestBlsPrivateKeyNotAfterConstructionId() {
        final var config = config();
        final var firstKey = Bytes.wrap("first-key");
        final var secondKey = Bytes.wrap("second-key");

        TssKeyFiles.writeBlsPrivateKey(config, 1L, firstKey);
        TssKeyFiles.writeBlsPrivateKey(config, 3L, secondKey);

        assertTrue(TssKeyFiles.readBlsPrivateKey(config, 0L).isEmpty());
        assertEquals(firstKey, TssKeyFiles.readBlsPrivateKey(config, 2L).orElseThrow());
        assertEquals(secondKey, TssKeyFiles.readBlsPrivateKey(config, 3L).orElseThrow());
    }

    @Test
    void readsLatestSchnorrKeyPairNotAfterConstructionId() {
        final var config = config();
        final var firstKeyPair = new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("private-a"), Bytes.wrap("public-a"));
        final var secondKeyPair = new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("private-b"), Bytes.wrap("public-b"));

        TssKeyFiles.writeSchnorrKeyPair(config, 7L, firstKeyPair);
        TssKeyFiles.writeSchnorrKeyPair(config, 9L, secondKeyPair);

        assertTrue(TssKeyFiles.readSchnorrKeyPair(config, 6L).isEmpty());
        assertEquals(firstKeyPair, TssKeyFiles.readSchnorrKeyPair(config, 8L).orElseThrow());
        assertEquals(secondKeyPair, TssKeyFiles.readSchnorrKeyPair(config, 9L).orElseThrow());
    }

    @Test
    void schnorrKeyPairRoundTripsThroughDelimitedBytes() {
        final var keyPair = new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("private"), Bytes.wrap("public"));

        final var bytes = keyPair.toDelimitedBytes();
        final var roundTripped = TssKeyFiles.SchnorrKeyPair.fromDelimited(bytes);

        assertEquals(keyPair, roundTripped);
        assertArrayEquals(bytes, roundTripped.toDelimitedBytes());
    }

    @Test
    void rejectsMalformedDelimitedSchnorrKeyPair() {
        assertThrows(IllegalArgumentException.class, () -> TssKeyFiles.SchnorrKeyPair.fromDelimited(new byte[] {3, 1}));
    }

    @Test
    void writesKeysWithOwnerOnlyPermissionsOnPosixFileSystems() throws IOException {
        assumeTrue(supportsPosixFilePermissions());
        final var config = config();
        final var blsKey = Bytes.wrap("bls-key");
        final var schnorrKeyPair = new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("private"), Bytes.wrap("public"));

        TssKeyFiles.writeBlsPrivateKey(config, 1L, blsKey);
        TssKeyFiles.writeSchnorrKeyPair(config, 2L, schnorrKeyPair);

        final var blsPath = tempDir.resolve("hints").resolve("1").resolve("bls.bin");
        final var schnorrPath = tempDir.resolve("wraps").resolve("2").resolve("schnorr.bin");
        assertEquals(OWNER_ONLY_FILE_PERMISSIONS, Files.getPosixFilePermissions(blsPath));
        assertEquals(OWNER_ONLY_FILE_PERMISSIONS, Files.getPosixFilePermissions(schnorrPath));
        assertEquals(OWNER_ONLY_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(blsPath.getParent()));
        assertEquals(OWNER_ONLY_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(schnorrPath.getParent()));
    }

    @Test
    void repairsLooseKeyFilePermissionsOnReadFromPosixFileSystems() throws IOException {
        assumeTrue(supportsPosixFilePermissions());
        final var key = Bytes.wrap("existing-key");
        final var path = tempDir.resolve("hints").resolve("1").resolve("bls.bin");
        Files.createDirectories(path.getParent());
        Files.write(path, key.toByteArray());
        Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));

        assertEquals(key, TssKeyFiles.readBlsPrivateKey(config(), 1L).orElseThrow());

        assertEquals(OWNER_ONLY_FILE_PERMISSIONS, Files.getPosixFilePermissions(path));
        assertEquals(OWNER_ONLY_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(path.getParent()));
    }

    private Configuration config() {
        return HederaTestConfigBuilder.create()
                .withValue("tss.tssKeysPath", tempDir.toAbsolutePath().toString())
                .getOrCreateConfig();
    }

    private boolean supportsPosixFilePermissions() {
        return tempDir.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
