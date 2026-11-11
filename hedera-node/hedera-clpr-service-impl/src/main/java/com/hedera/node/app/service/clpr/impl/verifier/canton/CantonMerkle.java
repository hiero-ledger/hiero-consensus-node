// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Canton's purpose-prefixed SHA-256 hashing: Merkle leaves, inner nodes, and the verdict signing input.
 * Port of {@code ClprCantonMerkle.sol}; every byte of the encoding must match the Solidity exactly.
 *
 * <p>Every hash input starts with a 4-byte big-endian purpose. A child digest enters an inner node as its
 * 34-byte multihash {@code 0x1220 || digest}.
 */
public final class CantonMerkle {
    public static final int PURPOSE_COMMON_METADATA = 6;
    public static final int PURPOSE_VIEW_COMMON_DATA = 8;
    public static final int PURPOSE_VIEW_PARTICIPANT_DATA = 9;
    public static final int PURPOSE_MERKLE_TREE_INNER_NODE = 3;
    public static final int PURPOSE_RESULT_MESSAGE_SIGNATURE = 38;

    private static final int DIGEST_LEN = 32;
    private static final byte MULTIHASH_SHA256 = 0x12;
    private static final byte MULTIHASH_LEN_32 = 0x20;

    private CantonMerkle() {}

    /** {@code SHA256(BE32(purpose) || BE32(len salt) || salt || BE32(len data) || data)}. */
    @NonNull
    public static byte[] leaf(final int purpose, @NonNull final byte[] salt, @NonNull final byte[] data) {
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(data, "data");
        final var md = sha256();
        md.update(be32(purpose));
        md.update(be32(salt.length));
        md.update(salt);
        md.update(be32(data.length));
        md.update(data);
        return md.digest();
    }

    /** {@code SHA256(BE32(3) || BE32(n) || mh(child_1) || ... || mh(child_n))}; children are 32-byte digests. */
    @NonNull
    public static byte[] inner(@NonNull final byte[][] children) {
        Objects.requireNonNull(children, "children");
        final var md = sha256();
        md.update(be32(PURPOSE_MERKLE_TREE_INNER_NODE));
        md.update(be32(children.length));
        for (final byte[] child : children) {
            updateMultihash(md, child);
        }
        return md.digest();
    }

    /** An inner node with one child. */
    @NonNull
    public static byte[] inner1(@NonNull final byte[] a) {
        return inner(new byte[][] {a});
    }

    /** An inner node with two children. */
    @NonNull
    public static byte[] inner2(@NonNull final byte[] a, @NonNull final byte[] b) {
        return inner(new byte[][] {a, b});
    }

    /** The 34-byte input a mediator signs: {@code mh(SHA256(BE32(38) || typedContent))}. */
    @NonNull
    public static byte[] verdictSigningInput(@NonNull final byte[] typedContent) {
        Objects.requireNonNull(typedContent, "typedContent");
        final var md = sha256();
        md.update(be32(PURPOSE_RESULT_MESSAGE_SIGNATURE));
        md.update(typedContent);
        final byte[] digest = md.digest();
        final byte[] out = new byte[2 + DIGEST_LEN];
        out[0] = MULTIHASH_SHA256;
        out[1] = MULTIHASH_LEN_32;
        System.arraycopy(digest, 0, out, 2, DIGEST_LEN);
        return out;
    }

    private static void updateMultihash(final MessageDigest md, final byte[] child) {
        Objects.requireNonNull(child, "child");
        if (child.length != DIGEST_LEN) {
            throw new IllegalArgumentException("Merkle child must be 32 bytes, was " + child.length);
        }
        md.update(MULTIHASH_SHA256);
        md.update(MULTIHASH_LEN_32);
        md.update(child);
    }

    private static byte[] be32(final int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
