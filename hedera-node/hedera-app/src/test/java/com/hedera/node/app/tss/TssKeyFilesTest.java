// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TssKeyFilesTest {
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

    private Configuration config() {
        return HederaTestConfigBuilder.create()
                .withValue("tss.tssKeysPath", tempDir.toAbsolutePath().toString())
                .getOrCreateConfig();
    }
}
