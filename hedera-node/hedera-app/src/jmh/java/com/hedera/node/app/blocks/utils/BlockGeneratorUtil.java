// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.utils;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates blocks for benchmarking and testing.
 * Uses object reuse pattern: creates items once, reuses them across all blocks.
 */
public class BlockGeneratorUtil {
    private static final int DEFAULT_ITEM_SIZE = 2000;
    private static final int PROTOBUF_OVERHEAD_ESTIMATE = 15;
    private static final int MIN_FILLER_ITEM_SIZE = 50;

    // Pre-allocated patterns
    private static final byte[] PATTERN_2KB = createPattern(2000);
    private static final byte[] PATTERN_128B = createPattern(128);

    // Cached reusable items (created once, reused many times)
    private static volatile BlockItem cachedStandardItem_2KB = null;
    private static volatile Long cachedStandardItemSize_2KB = null;

    private static byte[] createPattern(int size) {
        final byte[] pattern = new byte[size];
        for (int i = 0; i < size; i++) {
            pattern[i] = (byte) (i % 256);
        }
        return pattern;
    }

    /** Generates a block with default 2KB items. */
    public static Block generateBlock(long blockNumber, long blockSizeBytes) {
        return generateBlock(blockNumber, blockSizeBytes, DEFAULT_ITEM_SIZE);
    }

    /** Generates a block with specified item size. */
    public static Block generateBlock(long blockNumber, long blockSizeBytes, int itemSizeBytes) {
        validateInputs(blockSizeBytes, itemSizeBytes);

        final Instant timestamp = Instant.now();
        final List<BlockItem> items = new ArrayList<>();

        final BlockItem header = createBlockHeader(blockNumber, timestamp);
        items.add(header);
        long currentSize = estimateItemSize(header);

        final BlockItem proof = createBlockProof(blockNumber);
        final long proofSize = estimateItemSize(proof);

        if (currentSize + proofSize >= blockSizeBytes) {
            throw new IllegalArgumentException(String.format(
                    "Block size %d too small (header + proof = %d bytes)", blockSizeBytes, currentSize + proofSize));
        }

        // Get or create cached reusable item
        BlockItem standardItem;
        Long standardItemSize;

        if (itemSizeBytes == DEFAULT_ITEM_SIZE) {
            if (cachedStandardItem_2KB == null) {
                synchronized (BlockGeneratorUtil.class) {
                    if (cachedStandardItem_2KB == null) {
                        cachedStandardItem_2KB = createStandardItem(itemSizeBytes);
                        cachedStandardItemSize_2KB = estimateItemSize(cachedStandardItem_2KB);
                    }
                }
            }
            standardItem = cachedStandardItem_2KB;
            standardItemSize = cachedStandardItemSize_2KB;
        } else {
            standardItem = createStandardItem(itemSizeBytes);
            standardItemSize = estimateItemSize(standardItem);
        }

        final long targetSizeBeforeProof = blockSizeBytes - proofSize;

        // Reuse same item reference (massive performance gain!)
        while (currentSize + standardItemSize <= targetSizeBeforeProof) {
            items.add(standardItem);
            currentSize += standardItemSize;
        }

        // Add filler if needed to reach exact target size
        final long remainingSpace = targetSizeBeforeProof - currentSize;
        if (remainingSpace >= MIN_FILLER_ITEM_SIZE) {
            final BlockItem filler = createFillerItem(remainingSpace);
            items.add(filler);
        }

        items.add(proof);
        return new Block(items);
    }

    /** Generates multiple blocks with default 2KB items. */
    public static List<Block> generateBlocks(long startBlockNumber, int numBlocks, long blockSizeBytes) {
        return generateBlocks(startBlockNumber, numBlocks, blockSizeBytes, DEFAULT_ITEM_SIZE);
    }

    /** Generates multiple blocks with specified item size. */
    public static List<Block> generateBlocks(
            long startBlockNumber, int numBlocks, long blockSizeBytes, int itemSizeBytes) {
        final List<Block> blocks = new ArrayList<>(numBlocks);
        for (int i = 0; i < numBlocks; i++) {
            blocks.add(generateBlock(startBlockNumber + i, blockSizeBytes, itemSizeBytes));
        }
        return blocks;
    }

    private static void validateInputs(long blockSizeBytes, int itemSizeBytes) {
        if (blockSizeBytes <= 0) {
            throw new IllegalArgumentException("blockSizeBytes must be positive, got: " + blockSizeBytes);
        }
        if (itemSizeBytes <= 0) {
            throw new IllegalArgumentException("itemSizeBytes must be positive, got: " + itemSizeBytes);
        }
        if (blockSizeBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("blockSizeBytes exceeds max supported size");
        }
    }

    private static long estimateItemSize(BlockItem item) {
        return BlockItem.PROTOBUF.toBytes(item).length();
    }

    private static BlockItem createBlockHeader(long blockNumber, Instant timestamp) {
        final BlockHeader header = BlockHeader.newBuilder()
                .number(blockNumber)
                .blockTimestamp(Timestamp.newBuilder()
                        .seconds(timestamp.getEpochSecond())
                        .nanos(timestamp.getNano())
                        .build())
                .softwareVersion(
                        SemanticVersion.newBuilder().major(1).minor(0).patch(0).build())
                .build();
        return BlockItem.newBuilder().blockHeader(header).build();
    }

    private static BlockItem createStandardItem(int itemSizeBytes) {
        final byte[] data;

        if (itemSizeBytes == 2000) {
            data = new byte[itemSizeBytes];
            System.arraycopy(PATTERN_2KB, 0, data, 0, itemSizeBytes);
        } else {
            data = createPatternedData(itemSizeBytes, 0);
        }

        return BlockItem.newBuilder().signedTransaction(Bytes.wrap(data)).build();
    }

    private static BlockItem createFillerItem(long targetSizeBytes) {
        final long dataSize = Math.max(0, targetSizeBytes - PROTOBUF_OVERHEAD_ESTIMATE);
        final int arraySize = (int) Math.min(dataSize, Integer.MAX_VALUE);
        final byte[] data = createPatternedData(arraySize, 0);
        return BlockItem.newBuilder().signedTransaction(Bytes.wrap(data)).build();
    }

    private static BlockItem createBlockProof(long blockNumber) {
        final byte[] proofKey = new byte[128];
        System.arraycopy(PATTERN_128B, 0, proofKey, 0, 128);
        proofKey[0] = (byte) blockNumber;
        proofKey[1] = (byte) (blockNumber >> 8);
        proofKey[2] = (byte) (blockNumber >> 16);
        proofKey[3] = (byte) (blockNumber >> 24);

        final BlockProof proof = BlockProof.newBuilder()
                .block(blockNumber)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(proofKey))
                        .build())
                .build();
        return BlockItem.newBuilder().blockProof(proof).build();
    }

    private static byte[] createPatternedData(int size, int seed) {
        final byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((seed + i) % 256);
        }
        return data;
    }
}
