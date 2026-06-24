// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.relocatedIfNotPresentInWorkingDir;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.relocatedIfNotPresentWithCurrentPathPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyUtilsTest {

    @TempDir
    private File tempDir;

    @Test
    void writeKeyToCreatesKey() {
        final var newKeyFile = tempDir.toPath().resolve("ed25519.pem");
        final var edKey = ed25519PrivateKey();

        KeyUtils.writeKeyTo(edKey, newKeyFile.toString(), "testpass");
        final var recovered = Ed25519Utils.readKeyFrom(newKeyFile.toString(), "testpass");
        assertEquals(edKey, recovered);
    }

    @Test
    void writeKeyToCreatesPemWithOwnerOnlyPermissions() throws Exception {
        final var newKeyFile = tempDir.toPath().resolve("ed25519.pem");
        assumePosixPermissionsSupported(newKeyFile);

        KeyUtils.writeKeyTo(ed25519PrivateKey(), newKeyFile.toString(), "testpass");

        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(newKeyFile));
    }

    @Test
    void writeKeyToRepairsExistingPemPermissions() throws Exception {
        final var existingKeyFile = tempDir.toPath().resolve("ed25519.pem");
        assumePosixPermissionsSupported(existingKeyFile);
        Files.writeString(existingKeyFile, "stale", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(existingKeyFile, PosixFilePermissions.fromString("rw-r--r--"));

        KeyUtils.writeKeyTo(ed25519PrivateKey(), existingKeyFile.toString(), "testpass");

        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(existingKeyFile));
    }

    @Test
    void canRelocateAbsoluteFile() {
        final var absoluteLoc = "/tmp/genesis.pem";

        final var relocated =
                relocatedIfNotPresentWithCurrentPathPrefix(new File(absoluteLoc), "test", "src" + File.separator);

        assertEquals(absoluteLoc, relocated.getPath());
    }

    @Test
    void canRelocateWithMultipleHederaNodeDirs() {
        final var path = "hedera-node/data/config/hedera-node/0.0.3/genesis.pem";
        final var relocated =
                relocatedIfNotPresentWithCurrentPathPrefix(new File(path), "test", "src" + File.separator);

        assertEquals(
                relocated.getPath().lastIndexOf("hedera-node"),
                relocated.getPath().indexOf("hedera-node"));
    }

    @Test
    void canRelocateWithNoHederaNodeDir() {
        final var path = "data/config/0.0.3/genesis.pem";
        final var relocated =
                relocatedIfNotPresentWithCurrentPathPrefix(new File(path), "test", "src" + File.separator);

        assertTrue(relocated.getPath().contains("hedera-node"));
    }

    @Test
    void doesntRelocateIfFileExists() {
        final var existingLoc = "src/test/resources/vectors/genesis.pem";

        final var relocated =
                relocatedIfNotPresentWithCurrentPathPrefix(new File(existingLoc), "test", "src" + File.separator);

        assertEquals(existingLoc, relocated.getPath());
    }

    @Test
    void canRelocateFromWhenFileIsMissing() {
        final var missingLoc = "test/resources/vectors/genesis.pem";

        final var relocated =
                relocatedIfNotPresentWithCurrentPathPrefix(new File(missingLoc), "test", "src" + File.separator);

        assertEquals("src/test/resources/vectors/genesis.pem", relocated.getPath());
    }

    @Test
    void doesNotRelocateIfSegmentMissing() {
        final var missingLoc = "test/resources/vectors/genesis.pem";

        final var relocated =
                relocatedIfNotPresentWithCurrentPathPrefix(new File(missingLoc), "NOPE", "src" + File.separator);

        assertTrue(relocated.getPath().endsWith(missingLoc));
    }

    @Test
    void triesToRelocateObviouslyMissingPath() {
        final var notPresent = Paths.get("nowhere/src/main/resources/nothing.txt");

        final var expected = Paths.get("hedera-node/test-clients/src/main/resources/nothing.txt");

        final var actual = relocatedIfNotPresentInWorkingDir(notPresent);

        assertEquals(expected, actual);
    }

    @Test
    void readKeyFromRejectsTooLargePemInput() {
        final int maxPemBytes = 64 * 1024;
        final var tooLarge = new byte[maxPemBytes + 1];
        final var in = new ByteArrayInputStream(tooLarge);

        final var ex = assertThrows(
                IllegalArgumentException.class, () -> KeyUtils.readKeyFrom(in, "pass", KeyUtils.BC_PROVIDER));
        assertEquals("PEM input too large", ex.getMessage());
    }

    @Test
    void readKeyFromRejectsMissingPemObject() {
        final var in = new ByteArrayInputStream("not pem".getBytes(StandardCharsets.UTF_8));

        final var ex = assertThrows(
                IllegalArgumentException.class, () -> KeyUtils.readKeyFrom(in, "pass", KeyUtils.BC_PROVIDER));
        assertEquals("No PEM object found", ex.getMessage());
    }

    @Test
    void readKeyFromRejectsUnexpectedPemType() {
        final var pem = pem("PRIVATE KEY", "AA==");
        final var in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));

        final var ex = assertThrows(
                IllegalArgumentException.class, () -> KeyUtils.readKeyFrom(in, "pass", KeyUtils.BC_PROVIDER));
        assertEquals("Unexpected PEM type: PRIVATE KEY", ex.getMessage());
    }

    @Test
    void readKeyFromRejectsMultiplePemObjects() {
        final var pem = pem(KeyUtils.ENCRYPTED_PRIVATE_KEY, "AA==") + pem(KeyUtils.ENCRYPTED_PRIVATE_KEY, "AA==");
        final var in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));

        final var ex = assertThrows(
                IllegalArgumentException.class, () -> KeyUtils.readKeyFrom(in, "pass", KeyUtils.BC_PROVIDER));
        assertEquals("Multiple PEM objects not allowed", ex.getMessage());
    }

    @Test
    void readKeyFromWrapsPkcsExceptions() {
        // Correct PEM type, but invalid encrypted content, triggers a PKCSException that should be wrapped.
        final var pem = pem(KeyUtils.ENCRYPTED_PRIVATE_KEY, "AA==");
        final var in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));

        final var ex = assertThrows(
                IllegalArgumentException.class, () -> KeyUtils.readKeyFrom(in, "pass", KeyUtils.BC_PROVIDER));
        assertNotNull(ex.getCause());
    }

    @Test
    void readKeyFromFileWrapsIoExceptions() {
        final var missing = tempDir.toPath().resolve("missing.pem").toFile();

        final var ex = assertThrows(
                IllegalArgumentException.class, () -> KeyUtils.readKeyFrom(missing, "pass", KeyUtils.BC_PROVIDER));
        assertNotNull(ex.getCause());
    }

    private static String pem(final String type, final String base64Body) {
        return "-----BEGIN " + type + "-----\n" + base64Body + "\n-----END " + type + "-----\n";
    }

    private static EdDSAPrivateKey ed25519PrivateKey() {
        return new EdDSAPrivateKey(
                new EdDSAPrivateKeySpec(new byte[32], EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)));
    }

    private static void assumePosixPermissionsSupported(final Path path) throws Exception {
        final var pathToCheck =
                Files.exists(path) ? path : path.toAbsolutePath().getParent();
        Assumptions.assumeTrue(
                pathToCheck != null && Files.getFileStore(pathToCheck).supportsFileAttributeView("posix"),
                "POSIX permissions not supported on this filesystem");
    }
}
