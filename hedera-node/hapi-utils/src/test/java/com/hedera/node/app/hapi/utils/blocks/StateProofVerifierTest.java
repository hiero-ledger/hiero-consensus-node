// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests that {@link StateProofVerifier} climbs a Merkle path with whichever digest it is given, so a proof built
 * with SHA-256 verifies against a SHA-256 root and not against SHA-384.
 */
class StateProofVerifierTest {
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    void pathClimbsToTheRootWithTheGivenDigest(final DigestType digestType) {
        final int size = digestType.digestLength();
        final var path = pathFrom(filled(1, size), filled(2, size), filled(3, size));

        final var expectedRoot =
                expectedRoot(digestType.buildDigest(), filled(1, size), filled(2, size), filled(3, size));

        assertArrayEquals(
                expectedRoot, StateProofVerifier.computeBlockRootHashFromPath(path, digestType.buildDigest()));
        assertTrue(StateProofVerifier.verifyPath(path, expectedRoot, digestType.buildDigest()));
        assertEquals(size, expectedRoot.length);
    }

    @Test
    void sha256RootDoesNotVerifyWithASha384Digest() {
        final int size = DigestType.SHA_256.digestLength();
        final var path = pathFrom(filled(1, size), filled(2, size), filled(3, size));
        final var sha256Root =
                expectedRoot(DigestType.SHA_256.buildDigest(), filled(1, size), filled(2, size), filled(3, size));

        assertFalse(StateProofVerifier.verifyPath(path, sha256Root, DigestType.SHA_384.buildDigest()));
    }

    @Test
    void pathWithoutABaseHashDoesNotVerify() {
        final var path = MerklePath.newBuilder().build();

        assertFalse(StateProofVerifier.verifyPath(path, new byte[32], DigestType.SHA_256.buildDigest()));
    }

    /** A path from an explicit base hash: a right sibling, then a left sibling, then a single-child level. */
    private static MerklePath pathFrom(final byte[] baseHash, final byte[] rightSibling, final byte[] leftSibling) {
        return MerklePath.newBuilder()
                .hash(Bytes.wrap(baseHash))
                .siblings(List.of(
                        SiblingNode.newBuilder()
                                .isLeft(false)
                                .hash(Bytes.wrap(rightSibling))
                                .build(),
                        SiblingNode.newBuilder()
                                .isLeft(true)
                                .hash(Bytes.wrap(leftSibling))
                                .build(),
                        SiblingNode.newBuilder().isLeft(false).hash(Bytes.EMPTY).build()))
                .build();
    }

    /** The root of {@link #pathFrom}, built independently: {@code 0x02} joins two children, {@code 0x01} promotes one. */
    private static byte[] expectedRoot(
            final MessageDigest digest, final byte[] baseHash, final byte[] rightSibling, final byte[] leftSibling) {
        final var withRight = hash(digest, new byte[] {0x02}, baseHash, rightSibling);
        final var withLeft = hash(digest, new byte[] {0x02}, leftSibling, withRight);
        return hash(digest, new byte[] {0x01}, withLeft);
    }

    private static byte[] hash(final MessageDigest digest, final byte[]... parts) {
        digest.reset();
        for (final var part : parts) {
            digest.update(part);
        }
        return digest.digest();
    }

    private static byte[] filled(final int value, final int size) {
        final var bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
