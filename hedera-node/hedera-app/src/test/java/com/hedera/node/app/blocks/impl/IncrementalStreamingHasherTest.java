// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests {@link IncrementalStreamingHasher} for every block digest type, including that its saved state round-trips
 * with hashes sized by the digest it was built with.
 */
class IncrementalStreamingHasherTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("an empty tree's root is the leaf hash of no data")
    void emptyTreeRootIsLeafHashOfNoData(final DigestType digestType) {
        final var root = newHasher(digestType).computeRootHash();

        assertThat(root).isEqualTo(BlockImplUtils.hashLeaf(digestType.buildDigest(), new byte[0]));
        assertThat(root).hasSize(digestType.digestLength());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("a single leaf's root is its leaf hash")
    void singleLeafRootIsItsLeafHash(final DigestType digestType) {
        final var hasher = newHasher(digestType);
        hasher.addLeaf(leaf(1));

        assertThat(hasher.computeRootHash()).isEqualTo(BlockImplUtils.hashLeaf(digestType.buildDigest(), leaf(1)));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("three leaves fold into the expected root")
    void threeLeavesFoldIntoTheExpectedRoot(final DigestType digestType) {
        final var hasher = newHasher(digestType);
        hasher.addLeaf(leaf(1));
        hasher.addLeaf(leaf(2));
        hasher.addLeaf(leaf(3));

        final var leftPair = BlockImplUtils.hashInternalNode(
                digestType.buildDigest(), leafHash(digestType, 1), leafHash(digestType, 2));
        final var expected =
                BlockImplUtils.hashInternalNode(digestType.buildDigest(), leftPair, leafHash(digestType, 3));
        assertThat(hasher.computeRootHash()).isEqualTo(expected).hasSize(digestType.digestLength());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("resuming from intermediate state matches hashing without interruption")
    void resumingFromIntermediateStateMatchesUninterruptedHashing(final DigestType digestType) {
        final var uninterrupted = newHasher(digestType);
        final var firstHalf = newHasher(digestType);
        for (int i = 0; i < 5; i++) {
            uninterrupted.addLeaf(leaf(i));
            firstHalf.addLeaf(leaf(i));
        }

        final var resumed = new IncrementalStreamingHasher(
                digestType.buildDigest(),
                firstHalf.intermediateHashingState().stream()
                        .map(hash -> hash.toByteArray())
                        .toList(),
                firstHalf.leafCount());
        for (int i = 5; i < 11; i++) {
            uninterrupted.addLeaf(leaf(i));
            resumed.addLeaf(leaf(i));
        }

        assertThat(resumed.leafCount()).isEqualTo(uninterrupted.leafCount());
        assertThat(resumed.computeRootHash()).isEqualTo(uninterrupted.computeRootHash());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("saved state loads back with hashes sized by the digest type")
    void saveAndLoadRoundTripsWithTheDigestLength(final DigestType digestType) throws Exception {
        final var original = newHasher(digestType);
        for (int i = 0; i < 7; i++) {
            original.addLeaf(leaf(i));
        }
        final var file = tempDir.resolve("hasher-" + digestType);
        original.save(file);

        final var loaded = newHasher(digestType);
        loaded.load(file);

        // 8-byte leaf count, 4-byte hash count, then one digest-sized hash per pending subtree (3 for 7 leaves)
        assertThat(Files.size(file)).isEqualTo(8L + 4L + 3L * digestType.digestLength());
        assertThat(loaded.leafCount()).isEqualTo(original.leafCount());
        assertThat(loaded.computeRootHash()).isEqualTo(original.computeRootHash());
    }

    @Test
    @DisplayName("SHA-256 and SHA-384 give different roots for the same leaves")
    void sha256AndSha384RootsDiffer() {
        final var sha256 = newHasher(DigestType.SHA_256);
        final var sha384 = newHasher(DigestType.SHA_384);
        for (int i = 0; i < 4; i++) {
            sha256.addLeaf(leaf(i));
            sha384.addLeaf(leaf(i));
        }

        assertThat(sha256.computeRootHash()).hasSize(32);
        assertThat(sha384.computeRootHash()).hasSize(48);
    }

    /**
     * [PR 27696 comment, P2] At a SHA-384 -> SHA-256 switch the node restores block N-1's <b>persisted</b> intermediate
     * tree (which excludes N-1 itself) and then re-folds N-1's hash under the new digest
     * ({@code BlockStreamManagerImpl.init}). Whenever that fold completes a sibling pair (the switch lands after an
     * odd-numbered insertion), the fold must be computed under the NEW digest to match the producer.
     *
     * <p>A naive validator that reseeds its <b>already-folded</b> intermediate state under the new digest computes
     * that sibling fold under the OLD digest and diverges — this test pins that divergence so the validator cannot
     * silently regress to it. {@code StateChangesValidator.withBlockDigest} instead snapshots the pre-fold state and
     * re-folds the last block's hash under the new digest; this asserts that fixed approach reproduces the node's root.
     */
    @Test
    @DisplayName(
            "digest switch after an odd insertion: snapshot-then-refold matches the node; reseed-after-fold does not")
    void digestSwitchAfterOddInsertionMatchesNodeRestoreThenFold() {
        final byte[] block0 = new byte[48];
        final byte[] block1 = new byte[48];
        java.util.Arrays.fill(block0, (byte) 0xA1);
        java.util.Arrays.fill(block1, (byte) 0xB2);

        // Node path: restore block 0's persisted intermediate state (block 1 not yet folded in), switch to the NEW
        // digest, then append block 1 under the new digest — so the sibling fold is computed under SHA-256.
        final var afterBlock0 = new IncrementalStreamingHasher(DigestType.SHA_384.buildDigest(), List.of(), 0);
        afterBlock0.addNodeByHash(block0);
        final var preFoldState = afterBlock0.intermediateHashingState().stream()
                .map(hash -> hash.toByteArray())
                .toList();
        final var preFoldLeafCount = afterBlock0.leafCount();
        final var node =
                new IncrementalStreamingHasher(DigestType.SHA_256.buildDigest(), preFoldState, preFoldLeafCount);
        node.addNodeByHash(block1);
        final var nodeRoot = node.computeRootHash();

        // Fixed validator path (withBlockDigest): reseed from the snapshot taken BEFORE block 1 was folded, then
        // re-fold block 1 under the NEW digest — identical construction to the node, so the roots must match.
        final var fixedValidator =
                new IncrementalStreamingHasher(DigestType.SHA_256.buildDigest(), preFoldState, preFoldLeafCount);
        fixedValidator.addNodeByHash(block1);
        assertThat(fixedValidator.computeRootHash()).isEqualTo(nodeRoot);

        // Naive validator path: add block 0 and block 1 under the OLD digest (folding the sibling pair under SHA-384),
        // then reseed the already-folded state under the NEW digest. This must NOT equal the node's root — that
        // divergence is the bug the fix avoids.
        final var naive = new IncrementalStreamingHasher(DigestType.SHA_384.buildDigest(), List.of(), 0);
        naive.addNodeByHash(block0);
        naive.addNodeByHash(block1);
        final var naiveReseeded = new IncrementalStreamingHasher(
                DigestType.SHA_256.buildDigest(),
                naive.intermediateHashingState().stream()
                        .map(hash -> hash.toByteArray())
                        .toList(),
                naive.leafCount());
        assertThat(naiveReseeded.computeRootHash()).isNotEqualTo(nodeRoot);
    }

    /**
     * [PR 27696 comment, P2] When the final pre-switch block is <b>incomplete</b> (written but its async proof never
     * arrived) it sets {@code hashChainBroken} without being folded. On restart the node restores <b>that block's</b>
     * persisted intermediate tree — which still excludes the block's own hash and whose earlier sibling folds were
     * computed under the OLD digest — and folds the recovered block hash under the NEW digest
     * ({@code BlockStreamManagerImpl.init}, non-cutover branch). It never recomputes the earlier subtree.
     *
     * <p>Scenario: blocks 0 and 1 complete under SHA-384 (folding {@code P01} under SHA-384), block 2 is the incomplete
     * freeze block, then the stream switches to SHA-256. The validator's current state after blocks 0 and 1 is
     * byte-identical to block 2's persisted tree (both exclude block 2), so {@code withBlockDigest} must reseed from it
     * verbatim with NO replay, leaving {@code P01} under SHA-384; the {@code hashChainBroken} branch then folds the
     * recovered block 2 under SHA-256. The earlier (complete-predecessor) fix that snapshots the pre-fold state and
     * replays the last folded block would here recompute {@code P01} under SHA-256 and diverge — this pins that.
     */
    @Test
    @DisplayName("digest switch after a skipped odd block: reseed-current-no-replay matches the node; replay does not")
    void digestSwitchAfterSkippedBlockMatchesNodeRestoreThenFold() {
        final byte[] block0 = new byte[48];
        final byte[] block1 = new byte[48];
        final byte[] block2 = new byte[48];
        java.util.Arrays.fill(block0, (byte) 0xA1);
        java.util.Arrays.fill(block1, (byte) 0xB2);
        java.util.Arrays.fill(block2, (byte) 0xC3);

        // Node path: blocks 0 and 1 fold P01 under SHA-384; block 2 (incomplete) is NOT folded, so its persisted tree
        // excludes it. Restore that tree under the NEW digest and fold the recovered block 2 under SHA-256.
        final var afterBlocks0And1 = new IncrementalStreamingHasher(DigestType.SHA_384.buildDigest(), List.of(), 0);
        afterBlocks0And1.addNodeByHash(block0);
        afterBlocks0And1.addNodeByHash(block1);
        final var block2PersistedTree = afterBlocks0And1.intermediateHashingState().stream()
                .map(hash -> hash.toByteArray())
                .toList();
        final var block2PersistedLeafCount = afterBlocks0And1.leafCount();
        final var node = new IncrementalStreamingHasher(
                DigestType.SHA_256.buildDigest(), block2PersistedTree, block2PersistedLeafCount);
        node.addNodeByHash(block2);
        final var nodeRoot = node.computeRootHash();

        // Fixed validator (skipped-predecessor branch): block 2 was never folded, so the current state equals block 2's
        // persisted tree. Reseed from it under the NEW digest with NO replay, then fold the recovered block 2.
        final var current = new IncrementalStreamingHasher(DigestType.SHA_384.buildDigest(), List.of(), 0);
        current.addNodeByHash(block0);
        current.addNodeByHash(block1);
        final var fixedValidator = new IncrementalStreamingHasher(
                DigestType.SHA_256.buildDigest(),
                current.intermediateHashingState().stream()
                        .map(hash -> hash.toByteArray())
                        .toList(),
                current.leafCount());
        fixedValidator.addNodeByHash(block2);
        assertThat(fixedValidator.computeRootHash()).isEqualTo(nodeRoot);

        // Buggy path: the complete-predecessor logic reseeds from the snapshot taken BEFORE block 1 was folded, then
        // replays block 1 (the last folded block) under the NEW digest — recomputing P01 under SHA-256. Folding block 2
        // on top cannot repair that subtree, so the root must NOT match the node's.
        final var preFoldBeforeBlock1 = new IncrementalStreamingHasher(DigestType.SHA_384.buildDigest(), List.of(), 0);
        preFoldBeforeBlock1.addNodeByHash(block0);
        final var replayed = new IncrementalStreamingHasher(
                DigestType.SHA_256.buildDigest(),
                preFoldBeforeBlock1.intermediateHashingState().stream()
                        .map(hash -> hash.toByteArray())
                        .toList(),
                preFoldBeforeBlock1.leafCount());
        replayed.addNodeByHash(block1);
        replayed.addNodeByHash(block2);
        assertThat(replayed.computeRootHash()).isNotEqualTo(nodeRoot);
    }

    private static IncrementalStreamingHasher newHasher(final DigestType digestType) {
        return new IncrementalStreamingHasher(digestType.buildDigest(), List.of(), 0);
    }

    private static byte[] leafHash(final DigestType digestType, final int seed) {
        return BlockImplUtils.hashLeaf(digestType.buildDigest(), leaf(seed));
    }

    private static byte[] leaf(final int seed) {
        return new byte[] {(byte) seed, (byte) (seed >> 8), 0x42};
    }
}
