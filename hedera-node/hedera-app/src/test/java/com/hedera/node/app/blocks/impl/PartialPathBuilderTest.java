// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PartialPathBuilderTest {

    @Test
    void buildsStartingStatePathForSixteenBranchBlockRootTree() {
        final var previousBlockHash = hashWithByte(1);
        final var previousBlockRootsHash = hashWithByte(2);
        final var startingStateHash = hashWithByte(3);
        final var consensusHeaderRootHash = hashWithByte(4);
        final var branchesFiveThroughEightRoot = hashWithByte(5);
        final var reservedBranchesRoot = hashWithByte(6);
        final var siblingHashes = new MerkleSiblingHash[] {
            sibling(previousBlockRootsHash),
            sibling(hashWithByte(7)),
            sibling(branchesFiveThroughEightRoot),
            sibling(reservedBranchesRoot)
        };

        final var path = PartialPathBuilder.startingStateToBlockRoot(
                previousBlockHash, previousBlockRootsHash, startingStateHash, consensusHeaderRootHash, siblingHashes);

        assertThat(path.hash()).isEqualTo(startingStateHash);
        assertThat(path.nextPathIndex()).isEqualTo(2);
        assertThat(path.siblings()).hasSize(4);
        assertThat(path.siblings().get(0).hash()).isEqualTo(consensusHeaderRootHash);
        assertThat(path.siblings().get(0).isLeft()).isFalse();
        assertThat(path.siblings().get(1).isLeft()).isTrue();
        assertThat(path.siblings().get(2).hash()).isEqualTo(branchesFiveThroughEightRoot);
        assertThat(path.siblings().get(2).isLeft()).isFalse();
        assertThat(path.siblings().get(3).hash()).isEqualTo(reservedBranchesRoot);
        assertThat(path.siblings().get(3).isLeft()).isFalse();

        var actualRoot = startingStateHash;
        for (final var sibling : path.siblings()) {
            actualRoot = sibling.isLeft()
                    ? BlockImplUtils.hashInternalNode(sibling.hash(), actualRoot)
                    : BlockImplUtils.hashInternalNode(actualRoot, sibling.hash());
        }
        final var previousBranchesRoot = BlockImplUtils.hashInternalNode(previousBlockHash, previousBlockRootsHash);
        final var startingStateAndConsensusRoot =
                BlockImplUtils.hashInternalNode(startingStateHash, consensusHeaderRootHash);
        final var assignedBranchesRoot = BlockImplUtils.hashInternalNode(
                BlockImplUtils.hashInternalNode(previousBranchesRoot, startingStateAndConsensusRoot),
                branchesFiveThroughEightRoot);
        final var expectedRoot = BlockImplUtils.hashInternalNode(assignedBranchesRoot, reservedBranchesRoot);
        assertThat(actualRoot).isEqualTo(expectedRoot);
    }

    private static MerkleSiblingHash sibling(final Bytes hash) {
        return MerkleSiblingHash.newBuilder().siblingHash(hash).build();
    }

    private static Bytes hashWithByte(final int value) {
        final var hash = new byte[BlockImplUtils.HASH_SIZE];
        Arrays.fill(hash, (byte) value);
        return Bytes.wrap(hash);
    }
}
