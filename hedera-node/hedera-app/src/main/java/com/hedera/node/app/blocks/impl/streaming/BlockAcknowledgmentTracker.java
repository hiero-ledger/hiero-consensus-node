// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A class to track block acknowledgments for verified blocks by block nodes.
 */
public class BlockAcknowledgmentTracker {
    private static final Logger logger = LogManager.getLogger(BlockAcknowledgmentTracker.class);
    private final ConcurrentHashMap<String, AtomicLong> nodeAcknowledgments;
    private final int requiredAcknowledgments;
    private final boolean deleteFilesOnDisk;

    /**
     * @param requiredAcknowledgments
     * @param deleteFilesOnDisk
     */
    public BlockAcknowledgmentTracker(int requiredAcknowledgments, boolean deleteFilesOnDisk) {
        this.nodeAcknowledgments = new ConcurrentHashMap<>();
        this.requiredAcknowledgments = requiredAcknowledgments;
        this.deleteFilesOnDisk = deleteFilesOnDisk;
    }

    /**
     * @param connectionId the connection id
     * @param blockNumber the block number
     */
    public void trackAcknowledgment(@NonNull String connectionId, long blockNumber) {
        nodeAcknowledgments
                .computeIfAbsent(connectionId, k -> new AtomicLong(0))
                .set(blockNumber);

        if (!deleteFilesOnDisk) {
            checkBlockDeletion(blockNumber);
        }
    }

    private void checkBlockDeletion(long blockNumber) {
        long ackCount = nodeAcknowledgments.values().stream()
                .filter(ack -> ack.get() >= blockNumber)
                .count();

        if (ackCount >= requiredAcknowledgments) {
            logger.info(
                    "Block {} has received sufficient acknowledgments ({}). Ready for cleanup.",
                    blockNumber,
                    requiredAcknowledgments);
            // Trigger cleanup event
            onBlockReadyForCleanup(blockNumber);
        }
    }

    protected void onBlockReadyForCleanup(long blockNumber) {
        // This method can be overridden to implement actual cleanup logic
        logger.debug("Block {} is ready for cleanup", blockNumber);
    }

    public long getLastVerifiedBlock(@NonNull String nodeId) {
        return nodeAcknowledgments.getOrDefault(nodeId, new AtomicLong(0)).get();
    }
}
