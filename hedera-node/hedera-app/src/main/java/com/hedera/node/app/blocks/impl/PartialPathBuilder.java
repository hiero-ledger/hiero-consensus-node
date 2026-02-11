// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.blocks.HashUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A builder for creating a partial merkle path; specifically, the path from a block's starting state
 * hash subroot up to the block's root hash. This partial path is useful when combined with a leaf's
 * merkle path somewhere in a given state. The leaf's path can be extended with the siblings of the
 * partial path to produce a full merkle path from the state leaf to the block's root.
 */
class PartialPathBuilder {

    /**
     * Builds the partial merkle path from the starting state hash of a certain block up to that block's
     * root hash. Note that, while the block timestamp must eventually be added to complete any merkle path
     * dependent on the block root, the timestamp is intentionally excluded from the path's siblings here.
     *
     * @param previousBlockHash the block's previous block hash
     * @param prevBlockRootsHash the block's subroot of previous block root hashes
     * @param startingStateHash the block's starting state subroot hash
     * @param consensusHeaderRootHash the block's consensus header subroot hash
     * @param siblingHashes the block's merkle path sibling hashes <b>from the block's previous hash
     *                      to the block's root.</b> These will be used to <b>calculate</b> the sibling
     *                      hashes needed for this partial path, but are not the same set of siblings.
     */
    static MerklePath startingStateToBlockRoot(
            @NonNull final Bytes previousBlockHash,
            @NonNull final Bytes prevBlockRootsHash,
            @NonNull final Bytes startingStateHash,
            @NonNull final Bytes consensusHeaderRootHash,
            @NonNull final MerkleSiblingHash... siblingHashes) {
        requireNonNull(previousBlockHash);
        requireNonNull(prevBlockRootsHash);
        requireNonNull(startingStateHash);
        requireNonNull(consensusHeaderRootHash);
        requireNonNull(siblingHashes);

        final var blockAccessorSiblings = new SiblingNode[3];

        // Sibling 0: the consensus tree subroot
        blockAccessorSiblings[0] = SiblingNode.newBuilder()
                .isLeft(false)
                .hash(consensusHeaderRootHash)
                .build(); // consensus subroot

        // Sibling 1: calculate the second sibling, depth 5 node 1 (from prevBlockHash and prevBlockRootsHash)
        final var d5n1 = Bytes.wrap(HashUtils.joinHashes(
                CommonUtils.sha384DigestOrThrow(), previousBlockHash.toByteArray(), prevBlockRootsHash.toByteArray()));
        blockAccessorSiblings[1] =
                SiblingNode.newBuilder().isLeft(true).hash(d5n1).build();

        // Sibling 2: same as the block proof's third sibling, depth 4 node 2
        blockAccessorSiblings[2] = SiblingNode.newBuilder()
                .isLeft(false)
                .hash(siblingHashes[2].siblingHash())
                .build();

        // Build the (partial) merkle path. The requested leaf's path will be extended with this partial path when a
        // leaf is queried
        return MerklePath.newBuilder()
                .hash(startingStateHash)
                .siblings(blockAccessorSiblings)
				.nextPathIndex(2)
                .build();
    }
}
