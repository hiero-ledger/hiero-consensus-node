// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ethereum.Ssz.SszMerkleBranch;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SszTest {

    private static final HexFormat HEX = HexFormat.of();

    /** keccak-free SHA-256 of the empty input. */
    private static final byte[] SHA256_EMPTY =
            HEX.parseHex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    // Well-known SSZ zero-subtree hashes: zh[1] = sha256(zeros64); zh[k] = sha256(zh[k-1] || zh[k-1]).
    private static final byte[] ZERO_HASH_1 =
            HEX.parseHex("f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b");
    private static final byte[] ZERO_HASH_3 =
            HEX.parseHex("c78009fdf07fc56a11f122370658a353aaa542ed63e44c4bc15ff4cd105ab33c");

    @Nested
    class Sha256 {

        @Test
        void emptyInputMatchesKnownDigest() {
            assertThat(Ssz.sha256(new byte[0])).isEqualTo(SHA256_EMPTY);
        }

        @Test
        void multipleInputsAreConcatenatedBeforeHashing() {
            byte[] a = bytes(13, 0x10);
            byte[] b = bytes(19, 0x20);
            assertThat(Ssz.sha256(a, b)).isEqualTo(Ssz.sha256(concat(a, b)));
        }

        @Test
        void reusedAcrossCallsWithoutLeakingState() {
            // The per-thread digest is reused; a second call must not be polluted by the first.
            byte[] first = Ssz.sha256(bytes(8, 1));
            byte[] second = Ssz.sha256(new byte[0]);
            assertThat(first).isNotEqualTo(SHA256_EMPTY);
            assertThat(second).isEqualTo(SHA256_EMPTY);
        }
    }

    @Nested
    class PubkeyHash64 {

        @Test
        void zeroPubkeyHashesToZeroHash1() {
            // 48 zeros padded to 64 zeros, hashed once → sha256(zeros64) = zh1.
            assertThat(Ssz.pubkeyHash64(new byte[48])).isEqualTo(ZERO_HASH_1);
        }

        @Test
        void splitsIntoAFullChunkAndAZeroPaddedHalfChunk() {
            byte[] pubkey = bytes(48, 0x01);
            byte[] padded = new byte[64];
            System.arraycopy(pubkey, 0, padded, 0, 48);
            assertThat(Ssz.pubkeyHash64(pubkey)).isEqualTo(sha256(padded));
        }

        @Test
        void wrongLengthThrows() {
            assertThatThrownBy(() -> Ssz.pubkeyHash64(new byte[47]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pubkey must be 48 bytes, got 47");
        }
    }

    @Nested
    class Merkleize {

        @Test
        void singleLeafIsReturnedUnchanged() {
            byte[] leaf = bytes(32, 0x07);
            assertThat(Ssz.merkleize(new byte[][] {leaf})).isEqualTo(leaf);
        }

        @Test
        void twoLeavesHashAsAPair() {
            byte[] a = bytes(32, 0x10);
            byte[] b = bytes(32, 0x20);
            assertThat(Ssz.merkleize(new byte[][] {a, b})).isEqualTo(sha256(a, b));
        }

        @Test
        void twoZeroLeavesMatchZeroHash1() {
            assertThat(Ssz.merkleize(new byte[][] {new byte[32], new byte[32]})).isEqualTo(ZERO_HASH_1);
        }

        @Test
        void eightZeroLeavesMatchDepth3ZeroSubtreeRoot() {
            byte[][] zeros = new byte[8][];
            Arrays.fill(zeros, new byte[32]);
            assertThat(Ssz.merkleize(zeros)).isEqualTo(ZERO_HASH_3);
        }

        @Test
        void fourLeavesFoldTwoLevels() {
            byte[][] leaves = deterministicLeaves(4, 1);
            byte[] expected = sha256(sha256(leaves[0], leaves[1]), sha256(leaves[2], leaves[3]));
            assertThat(Ssz.merkleize(leaves)).isEqualTo(expected);
        }

        @Test
        void deepTreeMatchesIndependentMerkleization() {
            // depth 9 over 512 leaves — the execution-state-root branch depth.
            byte[][] leaves = deterministicLeaves(512, 0x33);
            assertThat(Ssz.merkleize(leaves)).isEqualTo(merkleizeIndependently(leaves));
        }

        @Test
        void leafOrderingChangesTheRoot() {
            byte[] a = bytes(32, 0x10);
            byte[] b = bytes(32, 0x20);
            assertThat(Ssz.merkleize(new byte[][] {a, b})).isNotEqualTo(Ssz.merkleize(new byte[][] {b, a}));
        }

        @Test
        void emptyInputThrows() {
            assertThatThrownBy(() -> Ssz.merkleize(new byte[0][]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chunk count must be a power of two, got 0");
        }

        @Test
        void nonPowerOfTwoThrows() {
            assertThatThrownBy(() -> Ssz.merkleize(deterministicLeaves(3, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chunk count must be a power of two, got 3");
        }
    }

    @Nested
    class Uint64Leaf {

        @Test
        void zeroIsAllZeroChunk() {
            assertThat(Ssz.uint64Leaf(0L)).isEqualTo(new byte[32]);
        }

        @Test
        void valueIsLittleEndianInTheFirstEightBytes() {
            byte[] leaf = Ssz.uint64Leaf(0x0102030405060708L);
            assertThat(leaf).hasSize(32);
            assertThat(Arrays.copyOfRange(leaf, 0, 8)).containsExactly(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01);
            // bytes 8..31 stay zero
            assertThat(Arrays.copyOfRange(leaf, 8, 32)).containsOnly(0);
        }

        @Test
        void maxValueFillsOnlyTheFirstEightBytes() {
            byte[] leaf = Ssz.uint64Leaf(-1L); // 0xFFFFFFFFFFFFFFFF
            for (int i = 0; i < 8; i++) {
                assertThat(leaf[i]).isEqualTo((byte) 0xFF);
            }
            assertThat(Arrays.copyOfRange(leaf, 8, 32)).containsOnly(0);
        }
    }

    @Nested
    class SyncCommitteeDomain {

        @Test
        void prefixesTheDomainTypeAndTruncatesTheForkDataRootTo28Bytes() {
            byte[] forkVersion = {0x05, 0x00, 0x00, 0x00};
            byte[] genesisValidatorsRoot = bytes(32, 0x4B);

            byte[] paddedVersion = new byte[32];
            System.arraycopy(forkVersion, 0, paddedVersion, 0, 4);
            byte[] forkDataRoot = sha256(paddedVersion, genesisValidatorsRoot);

            byte[] domain = Ssz.computeSyncCommitteeDomain(forkVersion, genesisValidatorsRoot);

            assertThat(domain).hasSize(32);
            // DOMAIN_SYNC_COMMITTEE = 0x07000000
            assertThat(Arrays.copyOfRange(domain, 0, 4)).containsExactly(0x07, 0x00, 0x00, 0x00);
            assertThat(Arrays.copyOfRange(domain, 4, 32)).isEqualTo(Arrays.copyOfRange(forkDataRoot, 0, 28));
        }
    }

    @Nested
    class SigningRoot {

        @Test
        void isSha256OfObjectRootAndDomain() {
            byte[] objectRoot = bytes(32, 0x1A);
            byte[] domain = bytes(32, 0x2B);
            assertThat(Ssz.computeSigningRoot(objectRoot, domain)).isEqualTo(sha256(objectRoot, domain));
        }

        @Test
        void zeroInputsMatchZeroHash1() {
            assertThat(Ssz.computeSigningRoot(new byte[32], new byte[32])).isEqualTo(ZERO_HASH_1);
        }

        @Test
        void wrongLengthObjectRootThrows() {
            assertThatThrownBy(() -> Ssz.computeSigningRoot(new byte[31], new byte[32]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("objectRoot must be 32 bytes, got 31");
        }

        @Test
        void wrongLengthDomainThrows() {
            assertThatThrownBy(() -> Ssz.computeSigningRoot(new byte[32], new byte[33]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("domain must be 32 bytes, got 33");
        }
    }

    @Nested
    class MerkleBranchInclusion {

        @Test
        void depthZeroProvesOnlyTheLeafItself() {
            byte[] leaf = bytes(32, 0x09);
            SszMerkleBranch sszMerkleBranch = new SszMerkleBranch(new byte[0][], 0, 0);
            assertThat(sszMerkleBranch.proves(leaf, leaf)).isTrue();
            assertThat(sszMerkleBranch.proves(leaf, bytes(32, 0x0A))).isFalse();
        }

        @Test
        void leftAndRightChildPositionsHashInTheRightOrder() {
            byte[] leaf = bytes(32, 0x01);
            byte[] sibling = bytes(32, 0x02);

            // index bit 0 clear → leaf is a left child → sha256(leaf || sibling)
            assertThat(new SszMerkleBranch(new byte[][] {sibling}, 1, 0).proves(leaf, sha256(leaf, sibling)))
                    .isTrue();
            assertThat(new SszMerkleBranch(new byte[][] {sibling}, 1, 0).proves(leaf, sha256(sibling, leaf)))
                    .isFalse();

            // index bit 0 set → leaf is a right child → sha256(sibling || leaf)
            assertThat(new SszMerkleBranch(new byte[][] {sibling}, 1, 1).proves(leaf, sha256(sibling, leaf)))
                    .isTrue();
            assertThat(new SszMerkleBranch(new byte[][] {sibling}, 1, 1).proves(leaf, sha256(leaf, sibling)))
                    .isFalse();
        }

        @Test
        void validProofVerifiesAndTamperingFails() {
            byte[] leaf = bytes(32, 9);
            byte[][] branch = {bytes(32, 10), bytes(32, 11), bytes(32, 12)};
            int index = 5; // bits 101
            byte[] root = foldBranch(leaf, branch, index);

            assertThat(new SszMerkleBranch(branch, 3, index).proves(leaf, root)).isTrue();
            // wrong index (sibling order flips at level 0)
            assertThat(new SszMerkleBranch(branch, 3, index ^ 1).proves(leaf, root))
                    .isFalse();
            // tampered leaf
            assertThat(new SszMerkleBranch(branch, 3, index).proves(bytes(32, 99), root))
                    .isFalse();
            // tampered root
            assertThat(new SszMerkleBranch(branch, 3, index).proves(leaf, bytes(32, 0x55)))
                    .isFalse();
        }

        @Test
        void siblingCountMismatchReturnsFalse() {
            byte[] leaf = bytes(32, 1);
            byte[][] branch = {bytes(32, 2), bytes(32, 3)};
            byte[] root = foldBranch(leaf, branch, 0);
            // declared depth 3 but only 2 siblings supplied
            assertThat(new SszMerkleBranch(branch, 3, 0).proves(leaf, root)).isFalse();
        }

        @Test
        void deepTreeInclusionProofVerifiesForEveryProbedLeaf() {
            // A real depth-9 tree (512 leaves) — execution-state-root branch depth. Build the tree,
            // derive each probe leaf's sibling path, and confirm the branch reproduces the root.
            byte[][] leaves = deterministicLeaves(512, 0x70);
            byte[] root = Ssz.merkleize(leaves);

            for (int index : new int[] {0, 1, 290, 511}) {
                byte[][] branch = branchFor(leaves, index);
                assertThat(new SszMerkleBranch(branch, 9, index).proves(leaves[index], root))
                        .as("leaf %d proves under the depth-9 root", index)
                        .isTrue();
                // a wrong position at the same depth must not verify
                assertThat(new SszMerkleBranch(branch, 9, index ^ 1).proves(leaves[index], root))
                        .as("leaf %d at flipped index must fail", index)
                        .isFalse();
            }
        }

        @Test
        void deepTreeRejectsACorruptedSibling() {
            byte[][] leaves = deterministicLeaves(512, 0x70);
            byte[] root = Ssz.merkleize(leaves);
            int index = 290;
            byte[][] branch = branchFor(leaves, index);
            branch[4] = bytes(32, 0x01); // corrupt one mid-level sibling

            assertThat(new SszMerkleBranch(branch, 9, index).proves(leaves[index], root))
                    .isFalse();
        }

        @Test
        void nonLeafSizedLeafThrows() {
            assertThatThrownBy(() ->
                            new SszMerkleBranch(new byte[][] {bytes(32, 1)}, 1, 0).proves(new byte[31], bytes(32, 2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("leaf must be 32 bytes, got 31");
        }

        @Test
        void nonLeafSizedSiblingThrows() {
            assertThatThrownBy(() ->
                            new SszMerkleBranch(new byte[][] {new byte[16]}, 1, 0).proves(bytes(32, 1), bytes(32, 2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("branch[0] must be 32 bytes, got 16");
        }
    }

    @Nested
    class GeneralizedIndices {

        // Guards the hand-derived gindices in the javadoc: gindex = 2^depth + leafIndex.
        @Test
        void executionStateRootMatchesGindex802() {
            assertThat((1 << Ssz.EXECUTION_STATE_ROOT_BRANCH_DEPTH) + Ssz.EXECUTION_STATE_ROOT_LEAF_INDEX)
                    .isEqualTo(802);
        }

        @Test
        void currentSyncCommitteeMatchesGindex86() {
            assertThat((1 << Ssz.CURRENT_SYNC_COMMITTEE_BRANCH_DEPTH) + Ssz.CURRENT_SYNC_COMMITTEE_LEAF_INDEX)
                    .isEqualTo(86);
        }

        @Test
        void nextSyncCommitteeMatchesGindex87() {
            assertThat((1 << Ssz.NEXT_SYNC_COMMITTEE_BRANCH_DEPTH) + Ssz.NEXT_SYNC_COMMITTEE_LEAF_INDEX)
                    .isEqualTo(87);
        }
    }

    // ── helpers ──

    /** Sibling path (bottom-up) for {@code leafIndex} in a balanced tree over {@code leaves}. */
    private static byte[][] branchFor(byte[][] leaves, int leafIndex) {
        int depth = Integer.numberOfTrailingZeros(leaves.length);
        byte[][] branch = new byte[depth][];
        byte[][] level = leaves.clone();
        int idx = leafIndex;
        for (int d = 0; d < depth; d++) {
            branch[d] = level[idx ^ 1];
            byte[][] next = new byte[level.length / 2][];
            for (int i = 0; i < next.length; i++) {
                next[i] = sha256(level[2 * i], level[2 * i + 1]);
            }
            level = next;
            idx >>>= 1;
        }
        return branch;
    }

    /** Folds a leaf up a Merkle branch per the consensus-spec ordering rule. */
    private static byte[] foldBranch(byte[] leaf, byte[][] branch, int index) {
        byte[] node = leaf;
        for (int i = 0; i < branch.length; i++) {
            node = ((index >>> i) & 1) == 1 ? sha256(branch[i], node) : sha256(node, branch[i]);
        }
        return node;
    }

    /** Pairwise-folds a power-of-two chunk list into a root — test-local merkleization. */
    private static byte[] merkleizeIndependently(byte[][] chunks) {
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

    private static byte[][] deterministicLeaves(int count, int seed) {
        byte[][] leaves = new byte[count][];
        for (int i = 0; i < count; i++) {
            leaves[i] = bytes(32, seed + i);
        }
        return leaves;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[]... inputs) {
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

    /**
     * Generates a byte array of the specified length, with each byte calculated based on the provided seed.
     */
    private static byte[] bytes(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
