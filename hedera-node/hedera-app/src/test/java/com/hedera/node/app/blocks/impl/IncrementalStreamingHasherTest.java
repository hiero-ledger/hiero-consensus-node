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
