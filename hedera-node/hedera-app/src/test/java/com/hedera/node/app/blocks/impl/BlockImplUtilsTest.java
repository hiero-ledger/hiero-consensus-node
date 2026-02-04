// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

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
        byte[] leftHash = MessageDigest.getInstance("SHA-384").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-384").digest("right".getBytes());
        byte[] combinedHash = BlockImplUtils.combine(leftHash, rightHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // SHA-384 produces 48-byte hash
    }

    @Test
    void testCombineEmptyHashes() throws NoSuchAlgorithmException {
        byte[] emptyHash = MessageDigest.getInstance("SHA-384").digest(new byte[0]);
        byte[] combinedHash = BlockImplUtils.combine(emptyHash, emptyHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // SHA-384 produces 48-byte hash
    }

    @Test
    void testCombineDifferentHashes() throws NoSuchAlgorithmException {
        byte[] leftHash = MessageDigest.getInstance("SHA-384").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-384").digest("right".getBytes());
        byte[] combinedHash1 = BlockImplUtils.combine(leftHash, rightHash);
        byte[] combinedHash2 = BlockImplUtils.combine(rightHash, leftHash);

        assertNotNull(combinedHash1);
        assertNotNull(combinedHash2);
        assertNotEquals(new String(combinedHash1), new String(combinedHash2));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void testCombineWithNull() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(new byte[0], null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashLeafByteArrayWithNullParamsThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf((byte[]) null));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(sha384DigestOrThrow(), (byte[]) null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashLeafBytesWithNullParamsThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf((Bytes) null));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(sha384DigestOrThrow(), (Bytes) null));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashLeaf(null, Bytes.EMPTY));
    }

    @Test
    void hashLeafAppendsLeafPrefix() throws NoSuchAlgorithmException {
        final Bytes expected = Bytes.fromBase64("z0BYGz7pzcJU2tAAD6jliPT9RKkH/tRTrGl0FfG7WgF8brmYMvQHoIUD4Fp148MC");

        final MessageDigest digest = MessageDigest.getInstance("SHA-384");
        final Bytes data = Bytes.fromHex("2a120816120c08d9d5d2c90610ffa8bba5033a00");
        digest.update(BlockImplUtils.LEAF_PREFIX);
        final Bytes computed = Bytes.wrap(digest.digest(data.toByteArray()));
        // Precondition: verify expected matches computed value
        assertEquals(expected, computed);

        // Test the Bytes overload
        final Bytes actual = BlockImplUtils.hashLeaf(data);
        assertEquals(expected, actual);

        // Test the byte array overload
        final byte[] actualArray = BlockImplUtils.hashLeaf(data.toByteArray());
        assertArrayEquals(expected.toByteArray(), actualArray);

        // Test byte array + digest overload
        digest.reset(); // Not necessary, but specifies intent
        final byte[] actualWithDigest = BlockImplUtils.hashLeaf(digest, data.toByteArray());
        assertArrayEquals(expected.toByteArray(), actualWithDigest);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeSingleChildWithNullParamThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNodeSingleChild(null));
    }

    @Test
    void hashInternalNodeSingleChildAppendsSingleNodePrefix() {
        final Bytes expected = Bytes.fromHex(
                "25eeda015d2d5506ca98944d615c7502baede45e5d03725184b9516c923485738c0bba382a6ef4840a02c6bb3c27c452");

        final MessageDigest digest = sha384DigestOrThrow();
        final Bytes data = Bytes.fromHex(
                "877a7ee7919309a359ee656d07e42504a2ab42c16089c235de87719c5ace1f00203c07a679d653d8d20458bf6c0ed143");
        digest.update(BlockImplUtils.SINGLE_CHILD_INTERNAL_NODE_PREFIX);
        final Bytes computed = Bytes.wrap(digest.digest(data.toByteArray()));
        // Precondition: verify expected matches computed value
        assertEquals(expected, computed);

        final Bytes actual = BlockImplUtils.hashInternalNodeSingleChild(data);
        assertEquals(expected, actual);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeBytesWithNullParamsThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(null, Bytes.EMPTY));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(Bytes.EMPTY, (Bytes) null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeByteArrayWithNullParamsThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode((byte[]) null, new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(new byte[0], null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeMixedWithNullParamsThrows() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode((Bytes) null, new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(Bytes.EMPTY, (byte[]) null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void hashInternalNodeWithDigestWithNullParamsThrows() {
        final var digest = sha384DigestOrThrow();
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(null, new byte[0], new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, null, new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.hashInternalNode(digest, new byte[0], null));
    }

    @Test
    void hashInternalNodeAppendsInternalNodePrefix() {
        final Bytes expected = Bytes.fromHex(
                "754ceb6301824804cd0488b2ed7a32e4594302f274c8363aa6696b427b3f586438ee367ba99320320e8df2d896425cd7");

        final MessageDigest digest = sha384DigestOrThrow();
        final Bytes data1 = Bytes.fromBase64("z0BYGz7pzcJU2tAAD6jliPT9RKkH/tRTrGl0FfG7WgF8brmYMvQHoIUD4Fp148MC");
        final Bytes data2 = Bytes.fromHex(
                "877a7ee7919309a359ee656d07e42504a2ab42c16089c235de87719c5ace1f00203c07a679d653d8d20458bf6c0ed143");
        BlockImplUtils.INTERNAL_NODE_PREFIX_BYTES.writeTo(digest);
        data1.writeTo(digest);
        data2.writeTo(digest);
        final Bytes computed = Bytes.wrap(digest.digest());
        // Precondition: verify expected matches computed value
        assertEquals(expected, computed);

        // Test the Bytes overload
        final Bytes actualFromBytes = BlockImplUtils.hashInternalNode(data1, data2);
        assertEquals(expected, actualFromBytes);

        // Test the byte arrays overload
        final byte[] data1Array = data1.toByteArray();
        final byte[] data2Array = data2.toByteArray();
        final byte[] actualFromArrays = BlockImplUtils.hashInternalNode(data1Array, data2Array);
        assertArrayEquals(expected.toByteArray(), actualFromArrays);

        // Test the explicit digest overload
        digest.reset(); // Not necessary, but specifies intent
        final byte[] actual = BlockImplUtils.hashInternalNode(digest, data1Array, data2Array);
        assertArrayEquals(expected.toByteArray(), actual);
    }

    @Test
    void hashedUnprefixedDoesNotMatchHashedPrefixed() {
        final var digest = sha384DigestOrThrow();
        final Bytes data = Bytes.wrap(new byte[] {9, 8, 7, 6});
        data.writeTo(digest);
        // e2fd3dfb508f0f533c7ecc813acb62f09b6a7675f18649eb06f2caea9296abb0cb68c73b324cafd1fc342e6b6380c7da
        final var computedNoPrefix = Bytes.wrap(digest.digest());

        digest.update(BlockImplUtils.LEAF_PREFIX);
        // 65832cbdef5675a6d51999ad0361dbbec33255afc88a781ea4355349465d5a8a5f1d6748b44a6fa99af518ff019a226e
        final var computedLeafPrefix = Bytes.wrap(digest.digest(data.toByteArray()));
        final var actualLeafPrefix = BlockImplUtils.hashLeaf(data);
        assertEquals(computedLeafPrefix, actualLeafPrefix);
        assertNotEquals(computedNoPrefix, actualLeafPrefix);

        digest.update(BlockImplUtils.SINGLE_CHILD_INTERNAL_NODE_PREFIX);
        // 264184f6b083b2927d15d0a36395c653b98c4ea679e9e5df3c50848728015338d0a6a2649058a8e4671194843034b51f
        data.writeTo(digest);
        final var computedSingleChildPrefix = Bytes.wrap(digest.digest());
        final var actualSingleChildPrefix = BlockImplUtils.hashInternalNodeSingleChild(data);
        assertEquals(computedSingleChildPrefix, actualSingleChildPrefix);
        assertNotEquals(computedNoPrefix, actualSingleChildPrefix);

        digest.update(BlockImplUtils.INTERNAL_NODE_PREFIX);
        // The internal node hash calculation requires two inputs, so use data twice
        data.writeTo(digest);
        data.writeTo(digest);
        // bd9a83a398a4cda37293296ad8a2e533233ea682cd6f53e5edcfbfaa53e648b229e82fc216f07cb8a8f2a98f1afcfc0f
        final var computedInternalNodePrefix = Bytes.wrap(digest.digest());
        final var actualInternalNodePrefix = BlockImplUtils.hashInternalNode(data, data);
        assertEquals(computedInternalNodePrefix, actualInternalNodePrefix);
        assertNotEquals(computedNoPrefix, actualInternalNodePrefix);

        // Test the mixed param types variant
        final var actualInternalMixedPrefix = BlockImplUtils.hashInternalNode(data, data.toByteArray());
        // Only equality check needed, as previous checks already guarantee the no prefix case is different
        assertEquals(computedInternalNodePrefix, actualInternalMixedPrefix);
    }
}
