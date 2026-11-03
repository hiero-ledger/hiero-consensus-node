// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * The Tendermint/CometBFT simple Merkle tree (RFC 6962 domain separation, SHA-256):
 * {@code leafHash = SHA256(0x00 || leaf)}, {@code innerHash = SHA256(0x01 || left || right)},
 * splitting at the largest power of two strictly less than the item count.
 *
 * <p>CometBFT uses this tree for the block-header hash (over the 14 cdc-encoded header
 * fields) and the validator-set hash (over encoded {@code SimpleValidator} leaves).
 */
public final class SeiMerkle {

    private static final byte[] LEAF_DOMAIN = {0x00};
    private static final byte[] INNER_DOMAIN = {0x01};

    private SeiMerkle() {}

    /** Computes the simple Merkle root of the given (unhashed) items. */
    @NonNull
    public static byte[] root(@NonNull final List<byte[]> items) {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            return sha256();
        }
        return subtreeRoot(items, 0, items.size());
    }

    @NonNull
    private static byte[] subtreeRoot(final List<byte[]> items, final int from, final int count) {
        if (count == 1) {
            return sha256(LEAF_DOMAIN, items.get(from));
        }
        final int split = splitPoint(count);
        return sha256(INNER_DOMAIN, subtreeRoot(items, from, split), subtreeRoot(items, from + split, count - split));
    }

    /** Largest power of two strictly less than {@code n}, for {@code n >= 2}. */
    static int splitPoint(final int n) {
        int k = 1;
        while (k * 2 < n) {
            k *= 2;
        }
        return k;
    }

    /** SHA-256 over the concatenation of the given parts. */
    @NonNull
    public static byte[] sha256(@NonNull final byte[]... parts) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        for (final byte[] part : parts) {
            digest.update(part);
        }
        return digest.digest();
    }
}
