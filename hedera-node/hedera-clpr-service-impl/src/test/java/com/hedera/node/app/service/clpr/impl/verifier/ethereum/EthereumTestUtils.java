// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Shared test-only helpers for the Ethereum sync-committee verifier suite.
 *
 * <p>These are deliberately <em>independent</em> reimplementations of the SSZ primitives — a plain
 * JDK SHA-256, a hand-rolled merkleization, and branch folding — rather than thin wrappers over
 * {@link Ssz}. Production {@code hash_tree_root}s ({@link BeaconHeader}, {@link SyncCommittee}) are
 * built on {@code Ssz.uint64Leaf} / {@code Ssz.merkleize}; computing the expected value with those
 * same methods would only prove the code equals itself. Keeping the oracle here, in one place,
 * removes the per-test-file duplication without sacrificing that independence.
 */
final class EthereumTestUtils {

    private static final HexFormat HEX = HexFormat.of();

    /** Well-known SSZ zero-subtree hashes: zh[1] = sha256(zeros64); zh[k] = sha256(zh[k-1] || zh[k-1]). */
    static final byte[] ZERO_HASH_1 = HEX.parseHex("f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b");

    static final byte[] ZERO_HASH_3 = HEX.parseHex("c78009fdf07fc56a11f122370658a353aaa542ed63e44c4bc15ff4cd105ab33c");

    private EthereumTestUtils() {}

    /** SHA-256 over the concatenation of {@code inputs}, via a fresh JDK digest (independent of {@link Ssz#sha256}). */
    static byte[] sha256(byte[]... inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] input : inputs) {
                digest.update(input);
            }
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pairwise-folds a power-of-two chunk list into a root — test-local merkleization. */
    static byte[] merkleizeIndependently(byte[][] chunks) {
        byte[][] level = chunks;
        while (level.length > 1) {
            byte[][] next = new byte[level.length / 2][];
            for (int i = 0; i < next.length; i++) {
                next[i] = sha256(level[2 * i], level[2 * i + 1]);
            }
            level = next;
        }
        return level[0];
    }

    /** Folds a leaf up a Merkle branch per the consensus-spec ordering rule (bit i = left/right at level i). */
    static byte[] foldBranch(byte[] leaf, byte[][] branch, int index) {
        byte[] node = leaf;
        for (int i = 0; i < branch.length; i++) {
            node = ((index >>> i) & 1) == 1 ? sha256(branch[i], node) : sha256(node, branch[i]);
        }
        return node;
    }

    /** SSZ uint64 chunk: value little-endian in the first 8 bytes of a zero chunk. */
    static byte[] uint64LeafLittleEndian(long value) {
        byte[] leaf = new byte[32];
        for (int i = 0; i < 8; i++) {
            leaf[i] = (byte) (value >>> (8 * i));
        }
        return leaf;
    }

    /**
     * Root of a sparse SSZ tree holding {@code leavesByGindex} at their generalized indices; every other subtree is a
     * zero chunk. Lets a test commit several leaves at different depths (e.g. the finalized root and the next
     * committee) under one state root.
     */
    static byte[] sparseRoot(final Map<Long, byte[]> leavesByGindex) {
        return sparseNode(leavesByGindex, 1L);
    }

    /** Sibling path (bottom-up) of {@code gindex} in the sparse tree of {@link #sparseRoot}. */
    static byte[][] sparseBranch(final Map<Long, byte[]> leavesByGindex, final long gindex) {
        final int depth = 63 - Long.numberOfLeadingZeros(gindex);
        final byte[][] branch = new byte[depth][];
        long node = gindex;
        for (int level = 0; level < depth; level++) {
            branch[level] = sparseNode(leavesByGindex, node ^ 1);
            node >>>= 1;
        }
        return branch;
    }

    private static byte[] sparseNode(final Map<Long, byte[]> leavesByGindex, final long gindex) {
        final byte[] leaf = leavesByGindex.get(gindex);
        if (leaf != null) {
            return leaf;
        }
        final boolean hasLeafBelow =
                leavesByGindex.keySet().stream().anyMatch(candidate -> isDescendant(candidate, gindex));
        return hasLeafBelow
                ? sha256(sparseNode(leavesByGindex, 2 * gindex), sparseNode(leavesByGindex, 2 * gindex + 1))
                : new byte[32];
    }

    private static boolean isDescendant(final long candidate, final long ancestor) {
        final int levelsBelow = Long.numberOfLeadingZeros(ancestor) - Long.numberOfLeadingZeros(candidate);
        return levelsBelow > 0 && (candidate >>> levelsBelow) == ancestor;
    }

    /** Deterministic byte fixture: {@code out[i] = seed + i*31 (mod 256)}. */
    static byte[] deterministicBytes(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
