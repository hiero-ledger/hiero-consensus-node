// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A class to track block acknowledgments for verified blocks by block nodes.
 */
public class BlockAcknowledgementTracker {
    private static final Logger logger = LogManager.getLogger(BlockAcknowledgementTracker.class);
    private final ConcurrentHashMap<String, AtomicLong> blockAcknowledgments;
    private final int requiredAcknowledgments;
    private final boolean deleteFilesOnDisk;

    /**
     * @param requiredAcknowledgments the required number of block acknowledgments before deleting files on disc
     * @param deleteFilesOnDisk whether to delete files on disk
     */
    public BlockAcknowledgementTracker(int requiredAcknowledgments, boolean deleteFilesOnDisk) {
        this.blockAcknowledgments = new ConcurrentHashMap<>();
        this.requiredAcknowledgments = requiredAcknowledgments;
        this.deleteFilesOnDisk = deleteFilesOnDisk;
    }

    /**
     * @param connectionId the connection id to update the block acknowledgment for
     * @param blockNumber the block number
     */
    public void trackAcknowledgment(@NonNull String connectionId, long blockNumber) {
        blockAcknowledgments
                .computeIfAbsent(connectionId, k -> new AtomicLong(0))
                .set(blockNumber);

        if (deleteFilesOnDisk) {
            checkBlockDeletion(blockNumber);
        }
    }

    /**
     * @param blockNumber the block number for which to check if the file is ready to be deleted
     */
    @VisibleForTesting
    public void checkBlockDeletion(long blockNumber) {
        long acknowledgementsCount = blockAcknowledgments.values().stream()
                .filter(ack -> ack.get() >= blockNumber)
                .count();

        if (acknowledgementsCount == requiredAcknowledgments) {
            logger.info(
                    "Block {} has received sufficient acknowledgments ({}). Ready for cleanup.",
                    blockNumber,
                    requiredAcknowledgments);
            // Trigger cleanup event
            onBlockReadyForCleanup(blockNumber);
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
        return blockAcknowledgments
                .getOrDefault(connectionId, new AtomicLong(0))
                .get();
    }
}
