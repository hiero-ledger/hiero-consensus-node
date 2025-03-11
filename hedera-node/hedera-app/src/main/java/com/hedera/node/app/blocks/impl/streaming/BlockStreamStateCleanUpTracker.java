// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A class to track block recognitions for blocks sent to block nodes.
 * Recognitions because the class keeps track of BlockAcknowledgements and SkipBlock responses.
 * Consensus node can receive SkipBlock PublishStreamResponse which indicates
 * that the block is received from another source which means that the block is being recognized.
 */
public class BlockStreamStateCleanUpTracker {
    private static final Logger logger = LogManager.getLogger(BlockStreamStateCleanUpTracker.class);

    private final BlockStreamStateManager blockStreamStateManager;
    private final ConcurrentHashMap<String, AtomicLong> blockRecognitions;
    private final int requiredRecognitions;
    private final boolean deleteFilesOnDisk;

    /**
     * @param blockStreamStateManager the block stream state manager to clean up block states
     * @param requiredRecognitions the required number of block recognitions before deleting files on disc
     * @param deleteFilesOnDisk whether to delete files on disk
     */
    public BlockStreamStateCleanUpTracker(
            @NonNull BlockStreamStateManager blockStreamStateManager,
            int requiredRecognitions,
            boolean deleteFilesOnDisk) {
        this.blockStreamStateManager = requireNonNull(blockStreamStateManager);
        this.blockRecognitions = new ConcurrentHashMap<>();
        this.requiredRecognitions = requiredRecognitions;
        this.deleteFilesOnDisk = deleteFilesOnDisk;
    }

    /**
     * @param connectionId the connection id to update the block recognition for
     * @param blockNumber the block number
     */
    public void trackBlockRecognition(@NonNull String connectionId, long blockNumber) {
        blockRecognitions.computeIfAbsent(connectionId, k -> new AtomicLong(0)).set(blockNumber);

        checkBlockDeletion(blockNumber);
    }

    /**
     * @param blockNumber the block number for which to check if the file is ready to be deleted
     */
    @VisibleForTesting
    public void checkBlockDeletion(long blockNumber) {
        long recognitionsCount = blockRecognitions.values().stream()
                .filter(ack -> ack.get() >= blockNumber)
                .count();

        if (recognitionsCount == requiredRecognitions) {
            logger.info(
                    "Block {} has received sufficient recognitions ({}). Ready for cleanup.",
                    blockNumber,
                    requiredRecognitions);
            // Trigger cleanup event
            blockStreamStateManager.cleanUpBlockState(blockNumber);

            if (deleteFilesOnDisk) {
                onBlockReadyForCleanup(blockNumber);
            }
        }
    }

    /**
     * @param blockNumber the block for which the file is ready to be deleted
     */
    @VisibleForTesting
    public void onBlockReadyForCleanup(long blockNumber) {
        logger.debug("Block {} is ready for cleanup", blockNumber);
    }

    /**
     * @param connectionId the connection id to get the last verified block for
     * @return the last verified block for this connection id
     */
    public long getLastVerifiedBlock(@NonNull String connectionId) {
        return blockRecognitions.getOrDefault(connectionId, new AtomicLong(0)).get();
    }
}
