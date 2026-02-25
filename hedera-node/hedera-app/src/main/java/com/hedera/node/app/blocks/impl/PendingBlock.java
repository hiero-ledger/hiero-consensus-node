// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents a block pending completion by the block hash signature needed for its block proof.
 *
 * @param number the block number
 * @param contentsPath the path to the block contents file, if not null
 * @param blockHash the block hash
 * @param previousBlockHash the previous block hash
 * @param proofBuilder the block proof builder
 * @param writer the block item writer
 * @param blockTimestamp the block timestamp
 * @param siblingHashes the sibling hashes needed to compute a block's root hash for an indirect proof
 */
public record PendingBlock(
        long number,
        @Nullable Path contentsPath,
        @NonNull Bytes blockHash,
        @NonNull Bytes previousBlockHash,
        @NonNull BlockProof.Builder proofBuilder,
        @NonNull BlockItemWriter writer,
        @NonNull Timestamp blockTimestamp,
        @NonNull MerkleSiblingHash... siblingHashes) {
    /**
     * Flushes this pending block to disk, including the sibling hashes needed
     * for a sequence of indirect proofs.
     */
    public PendingProof asPendingProof() {
        return PendingProof.newBuilder()
                .block(number)
                .blockHash(blockHash)
                .previousBlockHash(previousBlockHash)
                .blockTimestamp(blockTimestamp)
                // Sibling hashes are needed in case an indirect state proof is required. This will only be called
                // when flushing to disk, so we can safely build the proof builder here to get the sibling hashes.
                .siblingHashesFromPrevBlockRoot(List.of(siblingHashes))
                .build();
    }
}
