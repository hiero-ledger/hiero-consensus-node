// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.internal.Path;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PullVirtualTreeRequestTest {

    @Test
    @DisplayName("Round-trip serialization with path and hash")
    void roundTrip() {
        final long path = 42;
        final Hash hash = randomHash();
        final PullVirtualTreeRequest original = new PullVirtualTreeRequest(path, hash);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeRequest deserialized = PullVirtualTreeRequest.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(original.getPath(), deserialized.getPath());
        assertNotNull(deserialized.getHash());
        assertArrayEquals(
                original.getHash().copyToByteArray(), deserialized.getHash().copyToByteArray());
    }

    @Test
    @DisplayName("Round-trip serialization for root path")
    void roundTripRootPath() {
        final Hash hash = randomHash();
        final PullVirtualTreeRequest original = new PullVirtualTreeRequest(Path.ROOT_PATH, hash);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeRequest deserialized = PullVirtualTreeRequest.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(Path.ROOT_PATH, deserialized.getPath());
        assertNotNull(deserialized.getHash());
    }

    @Test
    @DisplayName("Round-trip serialization for terminating request (INVALID_PATH, null hash)")
    void roundTripTerminatingRequest() {
        final PullVirtualTreeRequest original = new PullVirtualTreeRequest(Path.INVALID_PATH, null);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeRequest deserialized = PullVirtualTreeRequest.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(Path.INVALID_PATH, deserialized.getPath());
        assertNull(deserialized.getHash());
    }

    @Test
    @DisplayName("getSizeInBytes matches actual serialized size")
    void sizeMatchesActualOutput() {
        final PullVirtualTreeRequest withHash = new PullVirtualTreeRequest(100, randomHash());
        final byte[] buf1 = new byte[withHash.getSizeInBytes()];
        final BufferedData out1 = BufferedData.wrap(buf1);
        withHash.writeTo(out1);
        assertEquals(withHash.getSizeInBytes(), out1.position());

        final PullVirtualTreeRequest noHash = new PullVirtualTreeRequest(Path.INVALID_PATH, null);
        final byte[] buf2 = new byte[noHash.getSizeInBytes()];
        final BufferedData out2 = BufferedData.wrap(buf2);
        noHash.writeTo(out2);
        assertEquals(noHash.getSizeInBytes(), out2.position());
    }

    @Test
    @DisplayName("Request with hash is larger than request without hash")
    void sizeWithHashLargerThanWithout() {
        final PullVirtualTreeRequest withHash = new PullVirtualTreeRequest(1, randomHash());
        final PullVirtualTreeRequest noHash = new PullVirtualTreeRequest(Path.INVALID_PATH, null);

        // With hash should include tag + varint length + 48 bytes of SHA-384 digest
        assertTrue(withHash.getSizeInBytes() > noHash.getSizeInBytes());
    }

    @Test
    @DisplayName("Large path value round-trips correctly")
    void largePath() {
        final long largePath = Long.MAX_VALUE - 1;
        final Hash hash = randomHash();
        final PullVirtualTreeRequest original = new PullVirtualTreeRequest(largePath, hash);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeRequest deserialized = PullVirtualTreeRequest.parseFrom(BufferedData.wrap(bytes));

        assertEquals(largePath, deserialized.getPath());
    }
}
