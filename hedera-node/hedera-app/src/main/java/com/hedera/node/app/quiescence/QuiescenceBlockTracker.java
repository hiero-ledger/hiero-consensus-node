// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.base.CompareTo;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Tracks all the information needed for quiescence for a specific block. This class is NOT thread-safe, it is expected
 * that all methods will be called from the same thread.
 */
public class QuiescenceBlockTracker {
    private final long blockNumber;
    private final QuiescenceController controller;
    private long relevantTransactionCount = 0;
    private Instant maxConsensusTime = Instant.EPOCH;
    private boolean blockFinalized = false;

    /**
     * Constructs a new block tracker.
     *
     * @param blockNumber the block number
     * @param controller  the quiescence controller
     */
    QuiescenceBlockTracker(final long blockNumber, @NonNull final QuiescenceController controller) {
        this.blockNumber = blockNumber;
        this.controller = controller;
    }

    /**
     * Notifies the block tracker that a transaction has been included in the block.
     *
     * @param txn the transaction included in the block
     */
    public void blockTransaction(@NonNull final Transaction txn) {
        if (controller.isDisabled()) {
            // If quiescence is not enabled, ignore these calls
            return;
        }
        if (blockFinalized) {
            controller.disableQuiescence("Block already finalized but received more transactions");
            return;
        }
        try {
            if (QuiescenceUtils.isRelevantTransaction(txn)) {
                relevantTransactionCount++;
            }
        } catch (final BadMetadataException e) {
            controller.disableQuiescence(e);
        }
    }

    /**
     * Notifies the block tracker that the consensus time has advanced. This is used to track the maximum consensus time
     * of a block. Note that consensus time can advance even when there are no transactions.
     *
     * @param newConsensusTime the new consensus time
     */
    public void consensusTimeAdvanced(@NonNull final Instant newConsensusTime) {
        if (controller.isDisabled()) {
            // If quiescence is not enabled, ignore these calls
            return;
        }
        if (blockFinalized) {
            controller.disableQuiescence("Block already finalized");
        }
        maxConsensusTime = CompareTo.max(maxConsensusTime, newConsensusTime);
    }

    /**
     * Notifies the block tracker that all transactions have been handled and the block is finalized. After this call,
     * no more transactions or consensus time updates should be sent to this block tracker.
     */
    public void finishedHandlingTransactions() {
        if (controller.isDisabled()) {
            // If quiescence is not enabled, ignore these calls
            return;
        }
        blockFinalized = true;
        controller.blockFinalized(this);
    }

    /**
     * Gets the block number.
     * @return the block number
     */
    public long getBlockNumber() {
        return blockNumber;
    }

    /**
     * Gets the number of relevant transactions in this block. Relevant transactions are explained in
     * {@link QuiescenceUtils#isRelevantTransaction(Transaction)}.
     *
     * @return the number of relevant transactions
     */
    public long getRelevantTransactionCount() {
        return relevantTransactionCount;
    }

    /**
     * Gets the maximum consensus time of this block.
     *
     * @return the maximum consensus time
     */
    @NonNull
    public Instant getMaxConsensusTime() {
        return maxConsensusTime;
    }
}
