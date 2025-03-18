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
 * A class to track BlockAcknowledgements for blocks sent to block nodes.
 */
public class BlockAcknowledgementsTracker {
    private static final Logger logger = LogManager.getLogger(BlockAcknowledgementsTracker.class);

    private static final int REQUIRED_ACKNOWLEDGEMENTS = 1;
    private final BlockStreamStateManager blockStreamStateManager;
    private final ConcurrentHashMap<String, AtomicLong> blockAcknowledgements;
    private final boolean deleteFilesOnDisk;
    private Long lastVerifiedBlock;

    /**
     * @param blockStreamStateManager the block stream state manager to clean up block states
     * @param deleteFilesOnDisk whether to delete files on disk
     */
    public BlockAcknowledgementsTracker(
            @NonNull BlockStreamStateManager blockStreamStateManager,
            boolean deleteFilesOnDisk) {
        this.blockStreamStateManager = requireNonNull(blockStreamStateManager);
        this.blockAcknowledgements = new ConcurrentHashMap<>();
        this.deleteFilesOnDisk = deleteFilesOnDisk;
        this.lastVerifiedBlock = -1L;
    }

    /**
     * @param connectionId the connection id to update the block acknowledgements for
     * @param blockNumber the block number
     */
    public void trackBlockAcknowledgements(@NonNull String connectionId, long blockNumber) {
        blockAcknowledgements
                .computeIfAbsent(connectionId, k -> new AtomicLong(0))
                .set(blockNumber);

        checkBlockDeletion(blockNumber);
    }

    /**
     * @param blockNumber the block number for which to check if the file is ready to be deleted
     */
    @VisibleForTesting
    public void checkBlockDeletion(long blockNumber) {
        long acknowledgementsCount = blockAcknowledgements.values().stream()
                .filter(ack -> ack.get() >= blockNumber)
                .count();

        if (acknowledgementsCount == REQUIRED_ACKNOWLEDGEMENTS) {
            logger.info(
                    "Block {} has received sufficient acknowledgements ({}). Ready for cleanup.",
                    blockNumber,
                    REQUIRED_ACKNOWLEDGEMENTS);

            // Trigger cleanup event
            blockStreamStateManager.cleanUpBlockState(blockNumber);
            lastVerifiedBlock = blockNumber;

            if (deleteFilesOnDisk) {
                onBlockReadyForCleanup(blockNumber);
            }
        }
    }

    /**
     * Cleans up all block files up to and including the specified block number.
     *
     * @param blockNumber the block for which the file is ready to be deleted
     */
    @VisibleForTesting
    public void onBlockReadyForCleanup(long blockNumber) {
        logger.debug("Block {} is ready for cleanup", blockNumber);
    }

    /**
     * @return the last verified block for this connection id
     */
    public long getLastVerifiedBlock() {
        return lastVerifiedBlock;
    }
}
