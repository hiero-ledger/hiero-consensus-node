// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.MerkleLeaf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
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
    public StateProof generateStateProof(
            @NonNull final PendingBlock currentPendingBlock,
            final long latestSignedBlockNumber,
            final Bytes latestSignedBlockSignature,
            @NonNull final Stream<PendingBlock> remainingPendingBlocks) {

        // Construct the necessary merkle paths for all blocks from [current, blockNumber - 1].
        // This makes it necessary to read each pending block, but not dequeue them
        // The current pending block was already POLLed off the pending blocks queue, so combine it in a stream
        // with all the other pending blocks still in the queue. Note that the resulting structure does NOT include
        // the next signed pending block)
        final Map<Long, PendingBlock> indirectProofBlocks =
                // Join the current pending block with the remaining pending blocks
                Streams.of(Stream.of(currentPendingBlock), remainingPendingBlocks)
                        .flatMap(s -> s)
                        .filter(pb -> pb.number() < latestSignedBlockNumber)
                        .collect(Collectors.toMap(PendingBlock::number, Function.identity()));

        // Construct all merkle paths for each pending block between [currentPendingBlock.number(),
        // latestSignedBlockNumber - 1]
        final MerklePath[] proofPaths =
                new MerklePath[3 * ((int) (latestSignedBlockNumber - currentPendingBlock.number()))];
        final long minBlockNum = currentPendingBlock.number();
        final int timestampDivider = (int) (latestSignedBlockNumber - minBlockNum);

        // Merke Path 1: the block timestamp paths
        var currentParentPath = proofPaths.length - 1;
        var currentBlockNum = latestSignedBlockNumber - 1;
        for (int i = 0; i < timestampDivider; i++) {
            final var innerBlock = indirectProofBlocks.get(currentBlockNum);
            final var timestampBytes = Timestamp.PROTOBUF.toBytes(innerBlock.blockTimestamp());
            final var mp1 = MerklePath.newBuilder()
                    .leaf(MerkleLeaf.newBuilder()
                            .blockConsensusTimestamp(timestampBytes)
                            .build())
                    .nextPathIndex(currentParentPath)
                    .build();
            proofPaths[i] = mp1;

            currentBlockNum--;
            currentParentPath -= 2;
        }

        // Construct all Merkle Path 2's and 3's
        var currentInnerBlock = minBlockNum;
        var currentParentPathMp2 = timestampDivider + 1;
        for (int i = timestampDivider; i < proofPaths.length; i += 2) {
            final var currentIndirectBlock = indirectProofBlocks.get(currentInnerBlock);
            if (currentIndirectBlock == null) {
                throw new IllegalStateException("Missing (contiguous) pending block for block " + currentInnerBlock);
            }

            // Merkle Path 2:
            final var mp2 = MerklePath.newBuilder()
                    .hash(currentIndirectBlock.previousBlockHash())
                    .siblings(Arrays.stream(currentIndirectBlock.siblingHashes())
                            .map(s -> SiblingNode.newBuilder()
                                    .isLeft(false)
                                    .hash(s.siblingHash())
                                    .build())
                            .toList())
                    // Point to the same parent as the timestamp path since that's the join point for both
                    .nextPathIndex(currentParentPathMp2)
                    .build();
            proofPaths[i] = mp2;

            // Merkle Path 3: subroot of mp1 and mp2 that points to the next hashing path
            final var nextPathIndex3 =
                    (currentIndirectBlock.number() == latestSignedBlockNumber - 1) ? -1 : currentParentPathMp2 + 1;
            final var mp3 =
                    MerklePath.newBuilder().nextPathIndex(nextPathIndex3).build();
            proofPaths[i + 1] = mp3;

            currentInnerBlock++;
            currentParentPathMp2 += 2;
        }

        return StateProof.newBuilder()
                .paths(proofPaths)
                .signedBlockProof(TssSignedBlockProof.newBuilder().blockSignature(latestSignedBlockSignature))
                .build();
    }
}
