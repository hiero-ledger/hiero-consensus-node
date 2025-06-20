// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.relocatedIfNotPresentInWorkingDir;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.relocatedIfNotPresentWithCurrentPathPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Paths;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyUtilsTest {

    @TempDir
    private File tempDir;

    @Test
    void writeKeyToCreatesKey() {
        final var newKeyFile = tempDir.toPath().resolve("ed25519.pem");
        final EdDSAPrivateKey edKey = new EdDSAPrivateKey(
                new EdDSAPrivateKeySpec(new byte[32], EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)));

        KeyUtils.writeKeyTo(edKey, newKeyFile.toString(), "testpass");
        final var recovered = Ed25519Utils.readKeyFrom(newKeyFile.toString(), "testpass");
        assertEquals(edKey, recovered);
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
}
