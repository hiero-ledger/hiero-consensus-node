// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.binary.SiblingHash;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.stream.Streams;
import org.hiero.base.crypto.Hash;

/**
 * Generator for state proofs used in indirect block proofs.
 * This class encapsulates the logic for constructing merkle paths needed to prove
 * blocks that precede the latest signed block.
 */
public class BlockStateProofGenerator {

    /**
     * The unsigned block sibling count includes the pending/unsigned block's timestamp
     */
    public static final int UNSIGNED_BLOCK_SIBLING_COUNT = BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK + 1;
    /**
     * The signed block sibling count doesn't include the signed block's timestamp
     */
    public static final int SIGNED_BLOCK_SIBLING_COUNT = BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK;

    /**
     * Each block's state proof consists of exactly three Merkle paths: the timestamp of the signed block,
     * previous block's hash + sibling hashes forming the path to the right sibling of the timestamp of the
     * signed block, and a trivial final parent path for the signed block's root
     */
    public static final int EXPECTED_MERKLE_PATH_COUNT = 3;

    /**
     * Index to the Merkle path containing hashes from the previous block's root to the right sibling of the
     * block's timestamp
     */
    public static final int BLOCK_CONTENTS_PATH_INDEX = 1;

    /**
     * Index to the final Merkle path representing the root hash of the signed block
     */
    public static final int FINAL_MERKLE_PATH_INDEX = 2;

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
                .collect(Collectors.toMap(PendingBlock::number, Function.identity()));

        final Map<Long, PendingBlock> indirectProofBlocks = allPendingBlocks.entrySet().stream()
                .filter(e -> e.getKey() < latestSignedBlockNumber)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // Construct all merkle paths for each pending block between [currentPendingBlock.number(),
        // latestSignedBlockNumber - 1]

        // Merkle Path 1: construct the block timestamp path
        final var tsBytes = Timestamp.PROTOBUF.toBytes(latestSignedBlockTimestamp);
        final var mp1 = MerklePath.newBuilder().timestampLeaf(tsBytes).nextPathIndex(FINAL_MERKLE_PATH_INDEX);

        // Merkle Path 2: enumerate all sibling hashes for all remaining blocks
        MerklePath.Builder mp2 = MerklePath.newBuilder()
                .hash(currentPendingBlock.prevBlockHash())
                .nextPathIndex(FINAL_MERKLE_PATH_INDEX);

        // Create a set of siblings for each indirect block, plus another set for the signed block
        final var totalSiblings =
                (indirectProofBlocks.size() * UNSIGNED_BLOCK_SIBLING_COUNT) + SIGNED_BLOCK_SIBLING_COUNT;
        final SiblingNode[] allSiblingHashes = new SiblingNode[totalSiblings];
        final long minBlockNum = currentPendingBlock.number();
        var currentBlockNum = minBlockNum;
        for (int i = 0; i < indirectProofBlocks.size(); i++) {
            // Convert first four sibling hashes
            final var blockSiblings = Arrays.stream(
                            indirectProofBlocks.get(currentBlockNum).siblingHashes())
                    .map(s -> new SiblingHash(s.isFirst(), new Hash(s.siblingHash())))
                    .toList();
            // Copy into the sibling hashes array
            final var firstSiblingIndex = i * UNSIGNED_BLOCK_SIBLING_COUNT;
            for (int j = 0; j < blockSiblings.size(); j++) {
                final var blockSibling = blockSiblings.get(j);
                allSiblingHashes[firstSiblingIndex + j] = SiblingNode.newBuilder()
                        .isLeft(blockSibling.isLeft())
                        .hash(blockSibling.hash().getBytes())
                        .build();
            }

            // Convert this pending block's timestamp into a sibling hash
            final var pbTsBytes = BlockImplUtils.hashTimestampLeaf(Timestamp.PROTOBUF.toBytes(
                    indirectProofBlocks.get(currentBlockNum).blockTimestamp()));
            // Add to the sibling hashes array
            final var pendingBlockTimestampSiblingIndex = firstSiblingIndex + UNSIGNED_BLOCK_SIBLING_COUNT - 1;
            // Timestamp is always a left sibling
            allSiblingHashes[pendingBlockTimestampSiblingIndex] =
                    SiblingNode.newBuilder().isLeft(true).hash(pbTsBytes).build();

            currentBlockNum++;
        }

        // Merkle Path 2 Continued: add sibling hashes for the signed block
        // Note: the timestamp for this (signed) block was provided in Merkle Path 1 above
        final var signedBlock = allPendingBlocks.get(latestSignedBlockNumber);
        final var signedBlockSiblings = signedBlock.siblingHashes();
        final var signedBlockFirstSiblingIndex = indirectProofBlocks.size() * UNSIGNED_BLOCK_SIBLING_COUNT;
        for (int i = 0; i < signedBlockSiblings.length; i++) {
            final var blockSibling = signedBlockSiblings[i];
            allSiblingHashes[signedBlockFirstSiblingIndex + i] = SiblingNode.newBuilder()
                    .isLeft(blockSibling.isFirst())
                    .hash(blockSibling.siblingHash())
                    .build();
        }
        mp2.siblings(Arrays.stream(allSiblingHashes).toList());

        // Merkle Path 3: the parent/block root path
        final var mp3 = MerklePath.newBuilder().nextPathIndex(FINAL_NEXT_PATH_INDEX);

        return StateProof.newBuilder()
                .paths(mp1.build(), mp2.build(), mp3.build())
                .signedBlockProof(TssSignedBlockProof.newBuilder().blockSignature(latestSignedBlockSignature))
                .build();
    }
}
