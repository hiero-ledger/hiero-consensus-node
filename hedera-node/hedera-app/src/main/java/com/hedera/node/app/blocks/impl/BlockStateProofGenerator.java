// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.stream.Streams;

/**
 * Generator for state proofs used in indirect block proofs.
 * This class encapsulates the logic for constructing merkle paths needed to prove
 * blocks that precede the latest signed block.
 */
public class BlockStateProofGenerator {

    /**
     * Each intermediate block contributes: its Merkle siblings, a null-hash sentinel for the
     * single-child internal node wrap, and its timestamp leaf hash
     */
    public static final int UNSIGNED_BLOCK_SIBLING_COUNT = BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK + 2;
    /**
     * The signed block contributes: its Merkle siblings and a null-hash sentinel for the
     * single-child internal node wrap (the timestamp lives in Merkle Path 1, not here)
     */
    public static final int SIGNED_BLOCK_SIBLING_COUNT = BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK + 1;

    /**
     * Each block's state proof consists of exactly three Merkle paths: the timestamp of the signed block,
     * the block root hash + sibling hashes forming the path to the single-child internal node at the same
     * level as the signed block's timestamp (encoded as a null-hash SiblingNode sentinel), and a trivial
     * final parent path for the signed block's root
     */
    public static final int EXPECTED_MERKLE_PATH_COUNT = 3;

    /**
     * Index to the Merkle path containing the block root hash and sibling hashes up through the signed block
     */
    public static final int BLOCK_CONTENTS_PATH_INDEX = 1;

    /**
     * Index to the final Merkle path representing the root hash of the signed block
     */
    public static final int ROOT_HASH_MERKLE_PATH_INDEX = 2;

    /**
     * Index indicating the end of the merkle path chain
     */
    public static final int FINAL_NEXT_PATH_INDEX = -1;

    /**
     * Constructs a state proof for a block that precedes the latest signed block. This involves creating merkle
     * paths for <b>all</b> pending blocks immediately preceding the latest signed block, and so must read from the
     * current pending blocks in memory.
     *
     * @param currentPendingBlock the pending block to generate a state proof for
     * @param latestSignedBlockNumber the block number of the latest signed block
     * @param latestSignedBlockSignature the signature of the latest signed block
     * @param remainingPendingBlocks stream of remaining pending blocks after the current one. This queue is
     *                               passed for <b>read-only</b> purposes; don't dequeue from it.
     * @return the constructed state proof
     * @throws IllegalStateException if the latest signed block is not strictly after the current pending block, if the
     *                               pending blocks contain duplicate block numbers or do not cover every block from the
     *                               current pending block through the latest signed block, or if any block does not
     *                               carry exactly {@code NUM_SIBLINGS_PER_BLOCK} sibling hashes
     */
    public static StateProof generateStateProof(
            @NonNull final PendingBlock currentPendingBlock,
            final long latestSignedBlockNumber,
            final Bytes latestSignedBlockSignature,
            final Timestamp latestSignedBlockTimestamp,
            @NonNull final Stream<PendingBlock> remainingPendingBlocks) {

        // Construct the necessary merkle paths for all blocks from [current, blockNumber - 1]. This makes it necessary
        // to read each pending block, but not dequeue them. The current pending block was already polled from the
        // pending blocks queue, so combine it in a stream with all the other pending blocks still in the queue.
        final Map<Long, PendingBlock> allPendingBlocks = Streams.of(
                        Stream.of(currentPendingBlock), remainingPendingBlocks)
                .flatMap(s -> s)
                .collect(Collectors.toMap(PendingBlock::number, Function.identity(), (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate pending block #%d in the pending block queue".formatted(a.number()));
                }));

        // An indirect proof requires the sibling hashes of every block from the current pending block up to and
        // including the signed block, so verify the pending blocks cover that entire range before constructing
        // anything, failing fast with the offending block number instead of an uninformative NPE below
        final long minBlockNum = currentPendingBlock.number();
        if (latestSignedBlockNumber <= minBlockNum) {
            throw new IllegalStateException(
                    "Cannot construct an indirect proof for pending block #%d from signed block #%d"
                            .formatted(minBlockNum, latestSignedBlockNumber));
        }
        for (long blockNum = minBlockNum + 1; blockNum <= latestSignedBlockNumber; blockNum++) {
            if (!allPendingBlocks.containsKey(blockNum)) {
                throw new IllegalStateException(
                        "Cannot construct an indirect proof for pending block #%d from signed block #%d because pending block #%d is missing"
                                .formatted(minBlockNum, latestSignedBlockNumber, blockNum));
            }
        }
        final int numIndirectBlocks = (int) (latestSignedBlockNumber - minBlockNum);

        // Construct all merkle paths for each pending block between [currentPendingBlock.number(),
        // latestSignedBlockNumber - 1]

        // Merkle Path 1: construct the block timestamp path
        final var tsBytes = Timestamp.PROTOBUF.toBytes(latestSignedBlockTimestamp);
        final var mp1 = MerklePath.newBuilder().timestampLeaf(tsBytes).nextPathIndex(ROOT_HASH_MERKLE_PATH_INDEX);

        // Merkle Path 2: starting from the block-to-prove's root hash, enumerate sibling hashes for all
        // subsequent blocks up through the signed block. A null-hash SiblingNode sentinel at the end encodes
        // the single-child internal node wrapping (depth2Node2) for the signed block.
        MerklePath.Builder mp2 = MerklePath.newBuilder()
                .hash(currentPendingBlock.blockHash())
                .nextPathIndex(ROOT_HASH_MERKLE_PATH_INDEX);

        // Skip the current block's own siblings (we start from its root hash) and collect siblings for each
        // subsequent indirect block, plus the signed block's siblings and null-hash sentinel
        final var siblings = new ArrayList<SiblingNode>();
        for (int i = 0; i < numIndirectBlocks - 1; i++) {
            final long currentBlockNum = minBlockNum + 1 + i;
            final var block = allPendingBlocks.get(currentBlockNum);
            // The verifier expects exactly NUM_SIBLINGS_PER_BLOCK sibling hashes per pending block. Fail fast if the
            // actual count drifts from that assumption, rather than emitting a proof that only remote verifiers can
            // reject.
            if (block.siblingHashes().length != BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK) {
                throw new IllegalStateException(
                        "Pending block #%d produced %d sibling hashes but exactly %d were expected"
                                .formatted(
                                        currentBlockNum,
                                        block.siblingHashes().length,
                                        BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK));
            }
            for (final var s : block.siblingHashes()) {
                siblings.add(SiblingNode.newBuilder()
                        .isLeft(s.isFirst())
                        .hash(s.siblingHash())
                        .build());
            }
            siblings.add(SiblingNode.newBuilder()
                    .build()); // Add the single-child internal node (with null-hash sentinal) for loop's current block
            // (s)
            final var hashedTs = BlockImplUtils.hashLeaf(Timestamp.PROTOBUF.toBytes(block.blockTimestamp()));
            siblings.add(SiblingNode.newBuilder().isLeft(true).hash(hashedTs).build());
        }

        // Merkle Path 2 Continued: add sibling hashes for the signed block, then a null-hash sentinel
        // to represent the single-child internal node wrapping (depth2Node2). The timestamp is in mp1.
        final var signedBlock = allPendingBlocks.get(latestSignedBlockNumber);
        // The verifier expects exactly NUM_SIBLINGS_PER_BLOCK sibling hashes for the signed block too; the
        // trailing null-hash sentinel below is added separately. Fail fast if the actual count drifts.
        if (signedBlock.siblingHashes().length != BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK) {
            throw new IllegalStateException("Signed block #%d produced %d sibling hashes but exactly %d were expected"
                    .formatted(
                            latestSignedBlockNumber,
                            signedBlock.siblingHashes().length,
                            BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK));
        }
        for (final var s : signedBlock.siblingHashes()) {
            siblings.add(SiblingNode.newBuilder()
                    .isLeft(s.isFirst())
                    .hash(s.siblingHash())
                    .build());
        }
        siblings.add(SiblingNode.newBuilder().build()); // null-hash sentinel
        mp2.siblings(siblings);

        // Merkle Path 3: the parent/block root path
        final var mp3 = MerklePath.newBuilder().nextPathIndex(FINAL_NEXT_PATH_INDEX);

        return StateProof.newBuilder()
                .paths(mp1.build(), mp2.build(), mp3.build())
                .signedBlockProof(TssSignedBlockProof.newBuilder().blockSignature(latestSignedBlockSignature))
                .build();
    }
}
