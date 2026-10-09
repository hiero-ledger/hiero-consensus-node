// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PartialPathBuilderTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    void buildsStartingStatePathForSixteenBranchBlockRootTree(final DigestType digestType) {
        assertPathClimbsToTheBlockRoot(digestType, hashWithByte(3, digestType.digestLength()));
    }

    @Test
    void buildsSha256PathFromASha384StartingStateHash() {
        // Until the platform switches its default digest, a SHA-256 block tree still holds a 48-byte state hash
        assertPathClimbsToTheBlockRoot(DigestType.SHA_256, hashWithByte(3, DigestType.SHA_384.digestLength()));
    }

    private static void assertPathClimbsToTheBlockRoot(final DigestType digestType, final Bytes startingStateHash) {
        final int hashSize = digestType.digestLength();
        final var previousBlockHash = hashWithByte(1, hashSize);
        final var previousBlockRootsHash = hashWithByte(2, hashSize);
        final var consensusHeaderRootHash = hashWithByte(4, hashSize);
        final var branchesFiveThroughEightRoot = hashWithByte(5, hashSize);
        final var reservedBranchesRoot = hashWithByte(6, hashSize);
        final var siblingHashes = new MerkleSiblingHash[] {
            sibling(previousBlockRootsHash),
            sibling(hashWithByte(7, hashSize)),
            sibling(branchesFiveThroughEightRoot),
            sibling(reservedBranchesRoot)
        };

        final var path = PartialPathBuilder.startingStateToBlockRoot(
                digestType.buildDigest(),
                previousBlockHash,
                previousBlockRootsHash,
                startingStateHash,
                consensusHeaderRootHash,
                siblingHashes);

        assertThat(path.hash()).isEqualTo(startingStateHash);
        assertThat(path.nextPathIndex()).isEqualTo(2);
        assertThat(path.siblings()).hasSize(4);
        assertThat(path.siblings().get(0).hash()).isEqualTo(consensusHeaderRootHash);
        assertThat(path.siblings().get(0).isLeft()).isFalse();
        assertThat(path.siblings().get(1).isLeft()).isTrue();
        assertThat(path.siblings().get(1).hash().length()).isEqualTo(hashSize);
        assertThat(path.siblings().get(2).hash()).isEqualTo(branchesFiveThroughEightRoot);
        assertThat(path.siblings().get(2).isLeft()).isFalse();
        assertThat(path.siblings().get(3).hash()).isEqualTo(reservedBranchesRoot);
        assertThat(path.siblings().get(3).isLeft()).isFalse();

        var actualRoot = startingStateHash;
        for (final var sibling : path.siblings()) {
            actualRoot = sibling.isLeft()
                    ? BlockImplUtils.hashInternalNode(digestType.buildDigest(), sibling.hash(), actualRoot)
                    : BlockImplUtils.hashInternalNode(digestType.buildDigest(), actualRoot, sibling.hash());
        }
        final var previousBranchesRoot =
                BlockImplUtils.hashInternalNode(digestType.buildDigest(), previousBlockHash, previousBlockRootsHash);
        final var startingStateAndConsensusRoot =
                BlockImplUtils.hashInternalNode(digestType.buildDigest(), startingStateHash, consensusHeaderRootHash);
        final var assignedBranchesRoot = BlockImplUtils.hashInternalNode(
                digestType.buildDigest(),
                BlockImplUtils.hashInternalNode(
                        digestType.buildDigest(), previousBranchesRoot, startingStateAndConsensusRoot),
                branchesFiveThroughEightRoot);
        final var expectedRoot =
                BlockImplUtils.hashInternalNode(digestType.buildDigest(), assignedBranchesRoot, reservedBranchesRoot);
        assertThat(actualRoot).isEqualTo(expectedRoot);
        assertThat(actualRoot.length()).isEqualTo(hashSize);
    }

    private static MerkleSiblingHash sibling(final Bytes hash) {
        return MerkleSiblingHash.newBuilder().siblingHash(hash).build();
    }

    private static Bytes hashWithByte(final int value, final int size) {
        final var hash = new byte[size];
        Arrays.fill(hash, (byte) value);
        return Bytes.wrap(hash);
    }
}
