// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha256DigestOrThrow;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
