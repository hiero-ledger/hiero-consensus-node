// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.MerklePathUtils;
import java.util.Random;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PullVirtualTreeRequestTest {

    @Test
    @DisplayName("Round-trip serialization with path and hash")
    void roundTrip() {
        final long path = 42;
        final Hash hash = randomHash(new Random(), Cryptography.DEFAULT_DIGEST_TYPE);
        final PullVirtualTreeRequest original = new PullVirtualTreeRequest(path, hash);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeRequest deserialized = PullVirtualTreeRequest.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(original.path(), deserialized.path());
        assertNotNull(deserialized.hashPrefix());
        assertEquals(
                PullVirtualTreeRequest.HASH_PREFIX_LENGTH,
                deserialized.hashPrefix().length());
        assertArrayEquals(
                hash.getBytes()
                        .slice(0, PullVirtualTreeRequest.HASH_PREFIX_LENGTH)
                        .toByteArray(),
                deserialized.hashPrefix().toByteArray());
        assertTrue(deserialized.matches(hash));
    }

    @Test
    @DisplayName("Round-trip serialization for root path")
    void roundTripRootPath() {
        final Hash hash = randomHash(new Random(), Cryptography.DEFAULT_DIGEST_TYPE);
        final PullVirtualTreeRequest original = new PullVirtualTreeRequest(MerklePathUtils.ROOT_PATH, hash);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeRequest deserialized = PullVirtualTreeRequest.parseFrom(BufferedData.wrap(bytes));

        assertNotNull(deserialized);
        assertEquals(MerklePathUtils.ROOT_PATH, deserialized.path());
        assertNotNull(deserialized.hashPrefix());
        assertTrue(deserialized.matches(hash));
    }

    @Test
    @DisplayName("getSizeInBytes matches actual serialized size")
    void sizeMatchesActualOutput() {
        final Random random = new Random();
        for (final long path : new long[] {MerklePathUtils.ROOT_PATH, 100}) {
            final PullVirtualTreeRequest request =
                    new PullVirtualTreeRequest(path, randomHash(random, Cryptography.DEFAULT_DIGEST_TYPE));
            final BufferedData out = BufferedData.wrap(new byte[request.getSizeInBytes()]);
            request.writeTo(out);
            assertEquals(request.getSizeInBytes(), out.position());
        }
    }

    @Test
    @DisplayName("Request carries only the hash prefix")
    void sizeWithHashPrefix() {
        final PullVirtualTreeRequest request =
                new PullVirtualTreeRequest(1, randomHash(new Random(), Cryptography.DEFAULT_DIGEST_TYPE));

        // path: tag + fixed64; hash prefix: tag + length + prefix bytes
        assertEquals(1 + Long.BYTES + 1 + 1 + PullVirtualTreeRequest.HASH_PREFIX_LENGTH, request.getSizeInBytes());
        assertTrue(PullVirtualTreeRequest.HASH_PREFIX_LENGTH < Cryptography.DEFAULT_DIGEST_TYPE.digestLength());
    }

    @Test
    @DisplayName("A hash matches if and only if its prefix is the same")
    void matches() {
        final Random random = new Random();
        final Hash hash = randomHash(random, Cryptography.DEFAULT_DIGEST_TYPE);
        final PullVirtualTreeRequest request = new PullVirtualTreeRequest(5, hash);

        assertTrue(request.matches(hash));
        assertTrue(request.matches(new Hash(hash.copyToByteArray(), Cryptography.DEFAULT_DIGEST_TYPE)));
        assertFalse(request.matches(randomHash(random, Cryptography.DEFAULT_DIGEST_TYPE)));

        // A difference in the prefix is a mismatch
        final byte[] prefixChanged = hash.copyToByteArray();
        prefixChanged[PullVirtualTreeRequest.HASH_PREFIX_LENGTH - 1] ^= 1;
        assertFalse(request.matches(new Hash(prefixChanged, Cryptography.DEFAULT_DIGEST_TYPE)));

        // A difference after the prefix isn't seen
        final byte[] suffixChanged = hash.copyToByteArray();
        suffixChanged[PullVirtualTreeRequest.HASH_PREFIX_LENGTH] ^= 1;
        assertTrue(request.matches(new Hash(suffixChanged, Cryptography.DEFAULT_DIGEST_TYPE)));
    }

    @Test
    @DisplayName("A null hash on the learner matches only a null hash on the teacher")
    void nullHashMatches() {
        final PullVirtualTreeRequest request = new PullVirtualTreeRequest(7, Cryptography.NULL_HASH);
        assertTrue(request.matches(Cryptography.NULL_HASH));
        assertFalse(request.matches(randomHash(new Random(), Cryptography.DEFAULT_DIGEST_TYPE)));
    }

    @Test
    @DisplayName("Parsing rejects a request without a hash prefix")
    void missingPrefix() {
        final byte[] bytes = new byte[16];
        final BufferedData out = BufferedData.wrap(bytes);
        ProtoWriterTools.writeLong(out, PullVirtualTreeRequest.FIELD_PULLREQUEST_PATH, 3);
        final BufferedData in = BufferedData.wrap(bytes, 0, (int) out.position());

        assertThrows(IllegalArgumentException.class, () -> PullVirtualTreeRequest.parseFrom(in));
    }

    @Test
    @DisplayName("Parsing rejects a hash prefix of the wrong length")
    void wrongPrefixLength() throws Exception {
        final Hash hash = randomHash(new Random(), Cryptography.DEFAULT_DIGEST_TYPE);
        final byte[] bytes = new byte[64];
        final BufferedData out = BufferedData.wrap(bytes);
        ProtoWriterTools.writeLong(out, PullVirtualTreeRequest.FIELD_PULLREQUEST_PATH, 3);
        ProtoWriterTools.writeBytes(out, PullVirtualTreeRequest.FIELD_PULLREQUEST_HASH_PREFIX, hash.getBytes());
        final BufferedData in = BufferedData.wrap(bytes, 0, (int) out.position());

        assertThrows(IllegalArgumentException.class, () -> PullVirtualTreeRequest.parseFrom(in));
    }

    @Test
    @DisplayName("Large path value round-trips correctly")
    void largePath() {
        final long largePath = Long.MAX_VALUE - 1;
        final Hash hash = randomHash(new Random(), Cryptography.DEFAULT_DIGEST_TYPE);
        final PullVirtualTreeRequest original = new PullVirtualTreeRequest(largePath, hash);

        final byte[] bytes = new byte[original.getSizeInBytes()];
        original.writeTo(BufferedData.wrap(bytes));

        final PullVirtualTreeRequest deserialized = PullVirtualTreeRequest.parseFrom(BufferedData.wrap(bytes));

        assertEquals(largePath, deserialized.path());
    }
}
