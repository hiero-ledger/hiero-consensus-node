// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha256DigestOrThrow;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

class BlockImplUtilsTest {
    @Test
    void testCombineNormalCase() throws NoSuchAlgorithmException {
        byte[] leftHash = MessageDigest.getInstance("SHA-256").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-256").digest("right".getBytes());
        byte[] combinedHash = BlockImplUtils.combine(sha384DigestOrThrow(), leftHash, rightHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // no digest specified defaults to SHA-384, 48-byte hash
    }

    @Test
    void testCombineEmptyHashes() throws NoSuchAlgorithmException {
        byte[] emptyHash = MessageDigest.getInstance("SHA-256").digest(new byte[0]);
        byte[] combinedHash = BlockImplUtils.combine(sha384DigestOrThrow(), emptyHash, emptyHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // no digest specified defaults to SHA-384, 48-byte hash
    }

    @Test
    void testCombineDifferentHashes() throws NoSuchAlgorithmException {
        byte[] leftHash = MessageDigest.getInstance("SHA-256").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-256").digest("right".getBytes());
        byte[] combinedHash1 = BlockImplUtils.combine(sha384DigestOrThrow(), leftHash, rightHash);
        byte[] combinedHash2 = BlockImplUtils.combine(sha384DigestOrThrow(), rightHash, leftHash);

        assertNotNull(combinedHash1);
        assertNotNull(combinedHash2);
        assertNotEquals(new String(combinedHash1), new String(combinedHash2));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void testCombineWithNull() {
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.combine(sha384DigestOrThrow(), null, new byte[0]));
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.combine(sha384DigestOrThrow(), new byte[0], null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashLeafByteArrayWithNullParamsThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(sha384DigestOrThrow(), (byte[]) null));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(sha256DigestOrThrow(), (byte[]) null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashLeafBytesWithNullParamsThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(sha384DigestOrThrow(), (Bytes) null));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(sha256DigestOrThrow(), (Bytes) null));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(null, Bytes.EMPTY));
    }

    @Test
    void hashLeafAppendsLeafPrefix() throws NoSuchAlgorithmException {
        final Bytes expected = Bytes.fromBase64("PC20jsnX7c+XYzhwCGLaSo/loMpfqYLL6vxvzycqJD0=");
        final Bytes expectedDefaultDigest = Bytes.fromHex(
                "cf40581b3ee9cdc254dad0000fa8e588f4fd44a907fed453ac697415f1bb5a017c6eb99832f407a08503e05a75e3c302");

        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final Bytes data = Bytes.fromHex("2a120816120c08d9d5d2c90610ffa8bba5033a00");
        digest.update(BlockImplUtils.LEAF_PREFIX);
        final Bytes computed = Bytes.wrap(digest.digest(data.toByteArray()));
        // Precondition: verify expected matches computed value
        assertEquals(expected, computed);

        // Test the Bytes overload (no digest specified defaults to SHA-384)
        final Bytes actual = BlockImplUtils.hashLeaf(sha384DigestOrThrow(), data);
        assertEquals(expectedDefaultDigest, actual);

        // Test the byte array overload (no digest specified defaults to SHA-384)
        final byte[] actualArray = BlockImplUtils.hashLeaf(sha384DigestOrThrow(), data.toByteArray());
        assertArrayEquals(expectedDefaultDigest.toByteArray(), actualArray);

        // Test byte array + digest overload
        digest.reset(); // Not necessary, but specifies intent
        final byte[] actualWithDigest = BlockImplUtils.hashLeaf(digest, data.toByteArray());
        assertArrayEquals(expected.toByteArray(), actualWithDigest);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeBytesWithNullParamsThrows() {
        assertThrows(
                NullPointerException.class,
                () -> BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), null, Bytes.EMPTY));
        assertThrows(
                NullPointerException.class,
                () -> BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), Bytes.EMPTY, (Bytes) null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeByteArrayWithNullParamsThrows() {
        assertThrows(
                NullPointerException.class,
                () -> BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), (byte[]) null, new byte[0]));
        assertThrows(
                NullPointerException.class,
                () -> BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), new byte[0], null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeMixedWithNullParamsThrows() {
        assertThrows(
                NullPointerException.class,
                () -> BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), (Bytes) null, new byte[0]));
        assertThrows(
                NullPointerException.class,
                () -> BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), Bytes.EMPTY, (byte[]) null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeWithDigestWithNullParamsThrows() {
        final var digest = sha256DigestOrThrow();
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(null, new byte[0], new byte[0]));
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, (byte[]) null, new byte[0]));
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, new byte[0], (byte[]) null));
    }

    @Test
    void hashInternalNodeAppendsInternalNodePrefix() {
        final Bytes expected = Bytes.fromHex("784e119f0efa9ff049e2da4370e15e2a24f2658c542322fa3805a9976b5ecbae");
        final Bytes expectedDefaultDigest = Bytes.fromHex(
                "754ceb6301824804cd0488b2ed7a32e4594302f274c8363aa6696b427b3f586438ee367ba99320320e8df2d896425cd7");

        final MessageDigest digest = sha256DigestOrThrow();
        final Bytes data1 = Bytes.fromBase64("z0BYGz7pzcJU2tAAD6jliPT9RKkH/tRTrGl0FfG7WgF8brmYMvQHoIUD4Fp148MC");
        final Bytes data2 = Bytes.fromHex(
                "877a7ee7919309a359ee656d07e42504a2ab42c16089c235de87719c5ace1f00203c07a679d653d8d20458bf6c0ed143");
        BlockImplUtils.INTERNAL_NODE_PREFIX_BYTES.writeTo(digest);
        data1.writeTo(digest);
        data2.writeTo(digest);
        final Bytes computed = Bytes.wrap(digest.digest());
        // Precondition: verify expected matches computed value
        assertEquals(expected, computed);

        // Test the Bytes overload (no digest specified defaults to SHA-384)
        final Bytes actualFromBytes = BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), data1, data2);
        assertEquals(expectedDefaultDigest, actualFromBytes);

        // Test the byte arrays overload (no digest specified defaults to SHA-384)
        final byte[] data1Array = data1.toByteArray();
        final byte[] data2Array = data2.toByteArray();
        final byte[] actualFromArrays = BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), data1Array, data2Array);
        assertArrayEquals(expectedDefaultDigest.toByteArray(), actualFromArrays);

        // Test the explicit digest overload
        digest.reset(); // Not necessary, but specifies intent
        final byte[] actual = BlockImplUtils.hashInternalNode(digest, data1Array, data2Array);
        assertArrayEquals(expected.toByteArray(), actual);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeBytesWithDigestThrowsOnNull() {
        final var digest = sha256DigestOrThrow();
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(null, Bytes.EMPTY, Bytes.EMPTY));
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, (Bytes) null, Bytes.EMPTY));
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, Bytes.EMPTY, (Bytes) null));
    }

    @Test
    void hashInternalNodeBytesWithDigestUsesProvidedAlgorithm() {
        final Bytes left = Bytes.wrap(new byte[] {1, 2, 3});
        final Bytes right = Bytes.wrap(new byte[] {4, 5, 6});

        final var referenceDigest = sha256DigestOrThrow();
        BlockImplUtils.INTERNAL_NODE_PREFIX_BYTES.writeTo(referenceDigest);
        left.writeTo(referenceDigest);
        right.writeTo(referenceDigest);
        final Bytes expected = Bytes.wrap(referenceDigest.digest());

        assertEquals(expected, BlockImplUtils.hashInternalNode(sha256DigestOrThrow(), left, right));
        assertEquals(32, expected.length());
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeMixedWithDigestThrowsOnNull() {
        final var digest = sha256DigestOrThrow();
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(null, Bytes.EMPTY, new byte[0]));
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, (Bytes) null, new byte[0]));
        assertThrows(
                NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, Bytes.EMPTY, (byte[]) null));
    }

    @Test
    void hashInternalNodeMixedWithDigestUsesProvidedAlgorithm() {
        final Bytes left = Bytes.wrap(new byte[] {1, 2, 3});
        final byte[] right = new byte[] {4, 5, 6};

        final var referenceDigest = sha256DigestOrThrow();
        BlockImplUtils.INTERNAL_NODE_PREFIX_BYTES.writeTo(referenceDigest);
        left.writeTo(referenceDigest);
        final Bytes expected = Bytes.wrap(referenceDigest.digest(right));

        assertEquals(expected, BlockImplUtils.hashInternalNode(sha256DigestOrThrow(), left, right));
        assertEquals(32, expected.length());
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void combineByteArraysWithDigestThrowsOnNull() {
        final var digest = sha256DigestOrThrow();
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(null, new byte[0], new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(digest, (byte[]) null, new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(digest, new byte[0], (byte[]) null));
    }

    @Test
    void combineByteArraysWithDigestUsesProvidedAlgorithm() {
        final var digest = sha256DigestOrThrow();
        final byte[] left = {1, 2, 3};
        final byte[] right = {4, 5, 6};

        final var referenceDigest = sha256DigestOrThrow();
        referenceDigest.update(left);
        final byte[] expected = referenceDigest.digest(right);

        assertArrayEquals(expected, BlockImplUtils.combine(digest, left, right));
        assertEquals(32, expected.length);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void combineBytesWithDigestThrowsOnNull() {
        final var digest = sha256DigestOrThrow();
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(null, Bytes.EMPTY, Bytes.EMPTY));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(digest, (Bytes) null, Bytes.EMPTY));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(digest, Bytes.EMPTY, (Bytes) null));
    }

    @Test
    void combineBytesWithDigestUsesProvidedAlgorithm() {
        final Bytes left = Bytes.wrap(new byte[] {1, 2, 3});
        final Bytes right = Bytes.wrap(new byte[] {4, 5, 6});

        final var referenceDigest = sha256DigestOrThrow();
        referenceDigest.update(left.toByteArray());
        final Bytes expected = Bytes.wrap(referenceDigest.digest(right.toByteArray()));

        assertEquals(expected, BlockImplUtils.combine(sha256DigestOrThrow(), left, right));
        assertEquals(32, expected.length());
    }

    @Test
    void hashedUnprefixedDoesNotMatchHashedPrefixed() {
        final var digest = sha256DigestOrThrow();
        final Bytes data = Bytes.wrap(new byte[] {9, 8, 7, 6});
        data.writeTo(digest);
        // 63d987d1c6d69751c17297f410f5b3547a65d096a8993b35bcb4f9cad054f176
        final var computedNoPrefix = Bytes.wrap(digest.digest());

        digest.update(BlockImplUtils.LEAF_PREFIX);
        // 70de4281b61ccc51ce0d1ef69cd28a4e28c2e6be36dc0be230d9d090ce07c94a
        final var computedLeafPrefix = Bytes.wrap(digest.digest(data.toByteArray()));
        // BlockImplUtils.hashLeaf(sha384DigestOrThrow(), Bytes) with no digest specified defaults to SHA-384, not
        // SHA-256
        final var actualLeafPrefix = BlockImplUtils.hashLeaf(sha384DigestOrThrow(), data);
        assertNotEquals(computedLeafPrefix, actualLeafPrefix);
        assertNotEquals(computedNoPrefix, actualLeafPrefix);

        digest.update(BlockImplUtils.INTERNAL_NODE_PREFIX);
        // The internal node hash calculation requires two inputs, so use data twice
        data.writeTo(digest);
        data.writeTo(digest);
        // f7396629d18804df928e70c1c54085a482ecba86e676349433d6eb2d357ba252
        final var computedInternalNodePrefix = Bytes.wrap(digest.digest());
        // BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), Bytes, Bytes) with no digest specified defaults to
        // SHA-384, not SHA-256
        final var actualInternalNodePrefix = BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), data, data);
        assertNotEquals(computedInternalNodePrefix, actualInternalNodePrefix);
        assertNotEquals(computedNoPrefix, actualInternalNodePrefix);

        // Test the mixed param types variant
        final var actualInternalMixedPrefix =
                BlockImplUtils.hashInternalNode(sha384DigestOrThrow(), data, data.toByteArray());
        // Only equality check needed, as previous checks already guarantee the no prefix case is different
        assertEquals(actualInternalNodePrefix, actualInternalMixedPrefix);
    }

    @Test
    void blockHashByBlockNumberSliceLengthFollowsHashSize() {
        // Three consecutive block hashes, laid out back-to-back. The caller selects the per-hash stride via
        // hashSize: 48 bytes for SHA-384 (the digestType=SHA_384 path) or 32 bytes for SHA-256 (digestType=SHA_256).
        // This is the mechanism BlockStreamInfoImpl relies on to honor the digestType setting.
        final long lastBlockNo = 5L;
        final long requestedBlockNo = 4L; // the middle of the three available hashes

        final byte[] sha384Bytes = new byte[48 * 3];
        for (int i = 0; i < sha384Bytes.length; i++) {
            sha384Bytes[i] = (byte) i;
        }
        final var sha384Slice =
                BlockImplUtils.blockHashByBlockNumber(Bytes.wrap(sha384Bytes), lastBlockNo, requestedBlockNo, 48);
        assertNotNull(sha384Slice);
        assertEquals(48L, sha384Slice.length());

        final byte[] sha256Bytes = new byte[32 * 3];
        for (int i = 0; i < sha256Bytes.length; i++) {
            sha256Bytes[i] = (byte) i;
        }
        final var sha256Slice =
                BlockImplUtils.blockHashByBlockNumber(Bytes.wrap(sha256Bytes), lastBlockNo, requestedBlockNo, 32);
        assertNotNull(sha256Slice);
        assertEquals(32L, sha256Slice.length());

        // The middle slice starts at a different offset under each stride (48 vs 32), so the bytes differ too.
        assertNotEquals(sha384Slice, sha256Slice);
    }

    @Test
    void leftPadToPrependsZeroBytesForShorterHash() {
        final byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 1); // non-zero so padding is distinguishable
        }
        final var padded = BlockImplUtils.leftPadTo(Bytes.wrap(raw), 48);
        assertEquals(48L, padded.length());
        final byte[] out = padded.toByteArray();
        for (int i = 0; i < 16; i++) {
            assertEquals(0, out[i], "leading byte " + i + " should be zero padding");
        }
        assertArrayEquals(raw, java.util.Arrays.copyOfRange(out, 16, 48));
    }

    @Test
    void leftPadToReturnsHashUnchangedWhenAlreadyStoredSize() {
        final byte[] raw = new byte[48];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        final var padded = BlockImplUtils.leftPadTo(Bytes.wrap(raw), 48);
        assertEquals(48L, padded.length());
        assertArrayEquals(raw, padded.toByteArray());
    }

    @Test
    void leftPadToThrowsWhenHashLongerThanStoredSize() {
        final var tooLong = Bytes.wrap(new byte[64]);
        assertThrows(IllegalArgumentException.class, () -> BlockImplUtils.leftPadTo(tooLong, 48));
    }

    @Test
    void stripLeadingPadReturnsRealHashWhenLeadingBytesAreZero() {
        final byte[] stored = new byte[48];
        for (int i = 16; i < 48; i++) {
            stored[i] = (byte) (i + 1); // 16 leading zeros, then a 32-byte real hash
        }
        final var real = BlockImplUtils.stripLeadingPad(Bytes.wrap(stored), 32);
        assertEquals(32L, real.length());
        assertArrayEquals(java.util.Arrays.copyOfRange(stored, 16, 48), real.toByteArray());
    }

    @Test
    void stripLeadingPadReturnsGenuineFullSizeEntryUnchanged() {
        // A genuine 48-byte SHA-384 hash whose leading bytes are non-zero must not be truncated to 32 bytes.
        final byte[] genuine = new byte[48];
        for (int i = 0; i < genuine.length; i++) {
            genuine[i] = (byte) (i + 1);
        }
        final var result = BlockImplUtils.stripLeadingPad(Bytes.wrap(genuine), 32);
        assertEquals(48L, result.length());
        assertArrayEquals(genuine, result.toByteArray());
    }

    @Test
    void stripLeadingPadReturnsEntryUnchangedWhenAlreadyRealSize() {
        final byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        final var result = BlockImplUtils.stripLeadingPad(Bytes.wrap(raw), 32);
        assertEquals(32L, result.length());
        assertArrayEquals(raw, result.toByteArray());
    }

    // --- realBlockHashByBlockNumber: stride given by the persisted compacted flag, reads real (unpadded) hash ---

    private static byte[] seq(final int len, final int start) {
        final byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) (start + i + 1); // non-zero so padding is distinguishable
        }
        return out;
    }

    private static byte[] padded48(final byte[] real32) {
        final byte[] out = new byte[48];
        System.arraycopy(real32, 0, out, 16, 32);
        return out;
    }

    @Test
    void realBlockHashByBlockNumberReadsNativeWindowAtActiveStride() {
        // Three native 32-byte entries for blocks 0,1,2 (post-compaction / all-native era). The persisted compacted
        // flag (true) gives the native stride directly.
        final byte[] b0 = seq(32, 0);
        final byte[] b1 = seq(32, 100);
        final byte[] b2 = seq(32, 200);
        final byte[] window = new byte[96];
        System.arraycopy(b0, 0, window, 0, 32);
        System.arraycopy(b1, 0, window, 32, 32);
        System.arraycopy(b2, 0, window, 64, 32);

        final var got = BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(window), 2L, 1L, 32, true);
        assertNotNull(got);
        assertEquals(32L, got.length());
        assertArrayEquals(b1, got.toByteArray());
    }

    @Test
    void realBlockHashByBlockNumberStripsPaddingFromAllPaddedWindow() {
        // Three padded 48-byte entries (each a padded 32-byte hash) for blocks 0,1,2; uncompacted (flag false).
        final byte[] r0 = seq(32, 0);
        final byte[] r1 = seq(32, 100);
        final byte[] r2 = seq(32, 200);
        final byte[] window = new byte[144];
        System.arraycopy(padded48(r0), 0, window, 0, 48);
        System.arraycopy(padded48(r1), 0, window, 48, 48);
        System.arraycopy(padded48(r2), 0, window, 96, 48);

        final var got = BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(window), 2L, 0L, 32, false);
        assertNotNull(got);
        assertEquals(32L, got.length());
        assertArrayEquals(r0, got.toByteArray());
    }

    @Test
    void realBlockHashByBlockNumberReadsMixedWindowGenuineAndPadded() {
        // Mixed 48-byte era (uncompacted, flag false): genuine SHA-384 hash (non-zero leading bytes) at block 0,
        // padded 32 at block 1.
        final byte[] genuine48 = seq(48, 0); // leading bytes non-zero -> genuine
        final byte[] real1 = seq(32, 200);
        final byte[] window = new byte[96];
        System.arraycopy(genuine48, 0, window, 0, 48);
        System.arraycopy(padded48(real1), 0, window, 48, 48);

        final var genuine = BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(window), 1L, 0L, 32, false);
        assertNotNull(genuine);
        assertEquals(48L, genuine.length());
        assertArrayEquals(genuine48, genuine.toByteArray());

        final var padded = BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(window), 1L, 1L, 32, false);
        assertNotNull(padded);
        assertEquals(32L, padded.length());
        assertArrayEquals(real1, padded.toByteArray());
    }

    @Test
    void realBlockHashByBlockNumberReturnsNullWhenOutOfRange() {
        final byte[] window = new byte[64]; // two native 32-byte entries for blocks 0,1
        assertEquals(null, BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(window), 1L, 2L, 32, true));
        assertEquals(null, BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(window), 1L, -1L, 32, true));
    }

    // --- appendTrailingBlockHash: uniform window, one-time compaction once no genuine hashes remain ---

    @Test
    void appendTrailingBlockHashPadsNewEntryWhileGenuineRemains() {
        // Mixed era, window not yet full: one genuine 48-byte entry for block 0, appending native hash for block 1.
        final byte[] genuine48 = seq(48, 0);
        final byte[] window = genuine48.clone(); // one entry, newBlockNo=1 so countExisting=1 matches one entry
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(window), 256, 48, 32, false, false);
        assertEquals(96L, result.window().length()); // grew by one 48-byte padded entry
        assertFalse(result.compacted());
        final byte[] out = result.window().toByteArray();
        // genuine entry unchanged
        assertArrayEquals(genuine48, java.util.Arrays.copyOfRange(out, 0, 48));
        // new entry left-padded to 48
        for (int i = 48; i < 64; i++) {
            assertEquals(0, out[i], "new entry leading byte should be zero padding");
        }
        assertArrayEquals(newNative, java.util.Arrays.copyOfRange(out, 64, 96));
    }

    @Test
    void appendTrailingBlockHashRollsOffWhileGenuineRemainsAtFullWindow() {
        // Full window of maxHashes=2 padded 48-byte entries, oldest genuine -> stays 48-mode, rolls off left.
        final byte[] genuine48 = seq(48, 0); // oldest, genuine
        final byte[] padded = padded48(seq(32, 100));
        final byte[] window = new byte[96];
        System.arraycopy(genuine48, 0, window, 0, 48);
        System.arraycopy(padded, 0, window, 48, 48);
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(window), 2, 48, 32, false, false);
        assertEquals(96L, result.window().length()); // stays maxHashes*48
        assertFalse(result.compacted());
        final byte[] out = result.window().toByteArray();
        // oldest genuine rolled off; previously-second entry now first
        assertArrayEquals(padded, java.util.Arrays.copyOfRange(out, 0, 48));
        // new entry padded to 48, rightmost
        assertArrayEquals(newNative, java.util.Arrays.copyOfRange(out, 64, 96));
    }

    @Test
    void appendTrailingBlockHashCompactsOncePostCutoverWhenNoGenuineRemain() {
        // Full window of maxHashes=2 entries, both padded (no genuine) -> compact to 32-mode then native append.
        final byte[] r0 = seq(32, 50);
        final byte[] r1 = seq(32, 100);
        final byte[] window = new byte[96];
        System.arraycopy(padded48(r0), 0, window, 0, 48);
        System.arraycopy(padded48(r1), 0, window, 48, 48);
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(window), 2, 48, 32, true, false);
        assertEquals(64L, result.window().length()); // compacted to maxHashes*32
        assertTrue(result.compacted());
        final byte[] out = result.window().toByteArray();
        // oldest (r0) rolled off; r1 compacted to native, new entry rightmost native
        assertArrayEquals(r1, java.util.Arrays.copyOfRange(out, 0, 32));
        assertArrayEquals(newNative, java.util.Arrays.copyOfRange(out, 32, 64));
    }

    @Test
    void appendTrailingBlockHashAppendsNativelyInPostCompactionEra() {
        // Already-native 32-mode window grows/rolls at 32.
        final byte[] r0 = seq(32, 50);
        final byte[] window = r0.clone(); // one native entry, countExisting = min(1,256)=1
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(window), 256, 48, 32, true, true);
        assertEquals(64L, result.window().length());
        assertTrue(result.compacted());
        final byte[] out = result.window().toByteArray();
        assertArrayEquals(r0, java.util.Arrays.copyOfRange(out, 0, 32));
        assertArrayEquals(newNative, java.util.Arrays.copyOfRange(out, 32, 64));
    }

    @Test
    void appendTrailingBlockHashKeepsFullSizeStrideAcrossPreCutoverDigestSwitch() {
        // A SHA-384 window that switches to SHA-256 before cutover must pad the new hashes, not append them natively
        final byte[] s0 = seq(48, 0);
        final byte[] s1 = seq(48, 50);
        final byte[] r2 = seq(32, 100);
        final byte[] r3 = seq(32, 150);

        var appended = BlockImplUtils.appendTrailingBlockHash(Bytes.wrap(s0), Bytes.EMPTY, 256, 48, 48, false, false);
        appended = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(s1), appended.window(), 256, 48, 48, false, appended.compacted());
        appended = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(r2), appended.window(), 256, 48, 32, false, appended.compacted());
        appended = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(r3), appended.window(), 256, 48, 32, false, appended.compacted());

        assertFalse(appended.compacted());
        assertEquals(4L * 48, appended.window().length());
        final var window = appended.window();
        assertArrayEquals(
                s0,
                BlockImplUtils.realBlockHashByBlockNumber(window, 3L, 0L, 32, false)
                        .toByteArray());
        assertArrayEquals(
                s1,
                BlockImplUtils.realBlockHashByBlockNumber(window, 3L, 1L, 32, false)
                        .toByteArray());
        assertArrayEquals(
                r2,
                BlockImplUtils.realBlockHashByBlockNumber(window, 3L, 2L, 32, false)
                        .toByteArray());
        assertArrayEquals(
                r3,
                BlockImplUtils.realBlockHashByBlockNumber(window, 3L, 3L, 32, false)
                        .toByteArray());
    }

    @Test
    void appendTrailingBlockHashNeverCompactsWhenPreSizeEqualsActiveSize() {
        // SHA-384 case (preSize == activeSize == 48): entries stay 48 bytes and the window is never marked compacted
        final byte[] e0 = seq(48, 0);
        final byte[] window = e0.clone();
        final byte[] newHash = seq(48, 100);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newHash), Bytes.wrap(window), 256, 48, 48, true, false);
        assertEquals(96L, result.window().length());
        assertFalse(result.compacted(), "a SHA-384 window stores full-size entries, so it is not compacted");
        final byte[] out = result.window().toByteArray();
        assertArrayEquals(e0, java.util.Arrays.copyOfRange(out, 0, 48));
        assertArrayEquals(newHash, java.util.Arrays.copyOfRange(out, 48, 96));
    }

    @Test
    void appendTrailingBlockHashDoesNotCompactPreCutoverWhileGenuineRemains() {
        final byte[] genuine48 = seq(48, 0);
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(genuine48), 256, 48, 32, false, false);
        assertEquals(96L, result.window().length());
        assertFalse(result.compacted());
        final byte[] out = result.window().toByteArray();
        assertArrayEquals(genuine48, java.util.Arrays.copyOfRange(out, 0, 48));
        assertArrayEquals(padded48(newNative), java.util.Arrays.copyOfRange(out, 48, 96));
    }

    @Test
    void appendTrailingBlockHashDoesNotCompactPreCutoverWhenFullyPadded() {
        final byte[] r0 = seq(32, 50);
        final byte[] r1 = seq(32, 100);
        final byte[] window = new byte[96];
        System.arraycopy(padded48(r0), 0, window, 0, 48);
        System.arraycopy(padded48(r1), 0, window, 48, 48);
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(window), 2, 48, 32, false, false);
        assertEquals(96L, result.window().length());
        assertFalse(result.compacted());
        final byte[] out = result.window().toByteArray();
        assertArrayEquals(padded48(r1), java.util.Arrays.copyOfRange(out, 0, 48));
        assertArrayEquals(padded48(newNative), java.util.Arrays.copyOfRange(out, 48, 96));
    }

    @Test
    void appendTrailingBlockHashDefersCompactionPostCutoverWhileGenuineRemains() {
        final byte[] genuine48 = seq(48, 0);
        final byte[] padded = padded48(seq(32, 100));
        final byte[] window = new byte[96];
        System.arraycopy(genuine48, 0, window, 0, 48);
        System.arraycopy(padded, 0, window, 48, 48);
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(window), 2, 48, 32, true, false);
        assertEquals(96L, result.window().length());
        assertFalse(result.compacted());
        final byte[] out = result.window().toByteArray();
        assertArrayEquals(padded, java.util.Arrays.copyOfRange(out, 0, 48));
        assertArrayEquals(padded48(newNative), java.util.Arrays.copyOfRange(out, 48, 96));
    }

    @Test
    void appendTrailingBlockHashIsIdempotentAfterCompaction() {
        final byte[] r0 = seq(32, 50);
        final byte[] r1 = seq(32, 100);
        final byte[] window = new byte[64];
        System.arraycopy(r0, 0, window, 0, 32);
        System.arraycopy(r1, 0, window, 32, 32);
        final byte[] newNative = seq(32, 200);

        final var result = BlockImplUtils.appendTrailingBlockHash(
                Bytes.wrap(newNative), Bytes.wrap(window), 2, 48, 32, true, true);
        assertEquals(64L, result.window().length());
        assertTrue(result.compacted());
        final byte[] out = result.window().toByteArray();
        assertArrayEquals(r1, java.util.Arrays.copyOfRange(out, 0, 32));
        assertArrayEquals(newNative, java.util.Arrays.copyOfRange(out, 32, 64));
    }

    @Test
    void appendTrailingBlockHashNeverCompactsAcrossRollOffPreCutover() {
        final int max = 4;
        Bytes window = Bytes.wrap(seq(48 * max, 0));
        for (int n = max; n < max + 300; n++) {
            final var appended =
                    BlockImplUtils.appendTrailingBlockHash(Bytes.wrap(seq(32, n)), window, max, 48, 32, false, false);
            window = appended.window();
            assertFalse(appended.compacted(), "must stay uncompacted at append " + n);
            assertEquals(48L * max, window.length(), "stride must stay 48 at append " + n);
        }
    }

    @Test
    void appendTrailingBlockHashCompactsExactlyOncePostCutover() {
        final int max = 4;
        Bytes window = Bytes.wrap(seq(48 * max, 0)); // entries are genuine (non-zero leading bytes)
        boolean compacted = false;
        // Genuine entries are gone after `max` appends; compaction fires on append index `max`.
        int firstCompact = -1;
        for (int i = 0; i < 20; i++) {
            final long n = max + i;
            final var appended = BlockImplUtils.appendTrailingBlockHash(
                    Bytes.wrap(seq(32, (int) n)), window, max, 48, 32, true, compacted);
            window = appended.window();
            compacted = appended.compacted();
            if (firstCompact < 0 && compacted) {
                firstCompact = i;
            }
            if (firstCompact < 0) {
                assertEquals(48L * max, window.length());
            } else {
                assertTrue(compacted, "must stay compacted after compaction at append " + i);
                assertEquals(32L * max, window.length(), "must stay native after compaction at append " + i);
            }
        }
        assertEquals(max, firstCompact, "compacts on the append after the last genuine entry has rolled off");
    }

    @Test
    void realBlockHashByBlockNumberStableAcrossEras() {
        final byte[] genuine0 = seq(48, 0);
        final byte[] r1 = seq(32, 100);
        final byte[] r2 = seq(32, 150);
        final byte[] r3 = seq(32, 200);

        // pre-cutover: only genuine block 0 (uncompacted window)
        assertArrayEquals(
                genuine0,
                BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(genuine0), 0L, 0L, 32, false)
                        .toByteArray());

        // cutover block: genuine block 0 followed directly by the first padded entry (block 1); uncompacted
        final byte[] cut = new byte[96];
        System.arraycopy(genuine0, 0, cut, 0, 48);
        System.arraycopy(padded48(r1), 0, cut, 48, 48);
        assertArrayEquals(
                genuine0,
                BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(cut), 1L, 0L, 32, false)
                        .toByteArray());
        assertArrayEquals(
                r1,
                BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(cut), 1L, 1L, 32, false)
                        .toByteArray());

        // mid roll-off: genuine rolled off, two padded entries for blocks 2,3; still uncompacted
        final byte[] mid = new byte[96];
        System.arraycopy(padded48(r2), 0, mid, 0, 48);
        System.arraycopy(padded48(r3), 0, mid, 48, 48);
        assertArrayEquals(
                r2,
                BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(mid), 3L, 2L, 32, false)
                        .toByteArray());

        // post-compaction: native entries for blocks 2,3 (compacted window)
        final byte[] post = new byte[64];
        System.arraycopy(r2, 0, post, 0, 32);
        System.arraycopy(r3, 0, post, 32, 32);
        assertArrayEquals(
                r3,
                BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(post), 3L, 3L, 32, true)
                        .toByteArray());
        assertArrayEquals(
                r2,
                BlockImplUtils.realBlockHashByBlockNumber(Bytes.wrap(post), 3L, 2L, 32, true)
                        .toByteArray());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Failing tests for review comments from tinker-michaelj on PR 27696.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * [PR 27696 comment, P1] {@code realBlockHashByBlockNumber} undercounts the entries of a genesis-origin window by
     * one. A young window built by the production writer holds one entry per block from the "block -1" hash-of-zero
     * through {@code lastBlockNo}, i.e. {@code lastBlockNo + 2} entries — but the reader derives its count from
     * {@code lastBlockNo + 1}. When the length is divisible by both strides (a 96-byte window that is 3x32 vs 2x48),
     * the off-by-one mis-derives the stride and splices the wrong bytes.
     *
     * <p>Repro mirrors the comment: append three distinct 32-byte hashes for blocks -1, 0, 1 through the production
     * writer, then read block 1 back. It should be the native 32-byte hash of block 1; today a 48-byte splice is
     * returned instead.
     */
    @Test
    void realBlockHashByBlockNumberIncludesGenesisEntryForYoungWindow() {
        final byte[] bMinus1 = seq(32, 0);
        final byte[] b0 = seq(32, 100);
        final byte[] b1 = seq(32, 200);

        // Born-SHA-256 history: no genuine pre-cutover hash ever existed, so the window is native (compacted) from its
        // first entry and entries stay native 32 bytes.
        var appended =
                BlockImplUtils.appendTrailingBlockHash(Bytes.wrap(bMinus1), Bytes.EMPTY, 256, 48, 32, false, true);
        appended = BlockImplUtils.appendTrailingBlockHash(Bytes.wrap(b0), appended.window(), 256, 48, 32, false, true);
        appended = BlockImplUtils.appendTrailingBlockHash(Bytes.wrap(b1), appended.window(), 256, 48, 32, false, true);
        assertEquals(96L, appended.window().length(), "three native 32-byte entries for blocks -1, 0, 1");
        assertTrue(appended.compacted(), "a born-native window is compacted (native-stride) throughout");

        // The persisted compacted flag gives the stride directly — no count, no off-by-one.
        final var got = BlockImplUtils.realBlockHashByBlockNumber(appended.window(), 1L, 1L, 32, true);
        assertNotNull(got);
        assertEquals(32L, got.length(), "block 1 is a native 32-byte SHA-256 hash");
        assertArrayEquals(b1, got.toByteArray(), "must return block 1's hash, not a 48-byte cross-entry splice");
    }

    /**
     * [PR 27696 comment, P2] {@code appendTrailingBlockHash} applies the genesis/preview count invariant
     * ({@code newBlockNo + 1}) even to a cutover record-history seed, which has no "block -1" sentinel and begins at
     * block 0. After {@code V0740BlockStreamSchema.restart()} removes the final record hash, three completed record
     * blocks (0, 1, 2) leave a 2-entry (96-byte) 48-byte seed, but {@code newBlockNo == 2} makes {@code countExisting
     * == 3}. The 96-byte seed is then decoded with stride 32, and appending the final 48-byte record hash throws
     * {@code IllegalArgumentException: Hash of length 48 exceeds stored size 32} — preventing the first cutover block
     * from starting.
     */
    @Test
    void appendTrailingBlockHashHandlesRecordSeedWithoutGenesisSentinel() {
        // Cutover seed: two genuine 48-byte SHA-384 record hashes for blocks 0 and 1 (block 2's final record hash was
        // trimmed by the schema and is about to be appended here).
        final byte[] recordBlock0 = seq(48, 0);
        final byte[] recordBlock1 = seq(48, 100);
        final byte[] seed = new byte[96];
        System.arraycopy(recordBlock0, 0, seed, 0, 48);
        System.arraycopy(recordBlock1, 0, seed, 48, 48);
        final byte[] finalRecordHash = seq(48, 200);

        // A record-migration seed is uncompacted (padded 48-byte); the flag — not an inferred count — fixes the stride.
        final var result = assertDoesNotThrow(
                () -> BlockImplUtils.appendTrailingBlockHash(
                        Bytes.wrap(finalRecordHash), Bytes.wrap(seed), 256, 48, 32, true, false),
                "appending the trimmed final record hash to a record-seed window must not throw");
        assertEquals(144L, result.window().length(), "three genuine 48-byte record entries for blocks 0, 1, 2");
        assertFalse(result.compacted(), "a record seed stays uncompacted until no genuine hash remains");
    }
}
