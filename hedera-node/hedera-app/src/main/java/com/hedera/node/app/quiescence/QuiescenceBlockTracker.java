package com.hedera.node.app.quiescence;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.base.CompareTo;
import org.hiero.consensus.model.transaction.Transaction;

public class QuiescenceBlockTracker {
    private final long blockNumber;
    private final QuiescenceController controller;
    private long relevantTransactionCount = 0;
    private Instant maxConsensusTime = Instant.EPOCH;
    private boolean blockFinalized = false;


    public QuiescenceBlockTracker(final long blockNumber, final QuiescenceController controller) {
        this.blockNumber = blockNumber;
        this.controller = controller;
    }

    public void blockTransaction(@NonNull final Transaction txn) {
        if(controller.isDisabled()){
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

    public void consensusTimeAdvanced(@NonNull final Instant newConsensusTime) {
        if(controller.isDisabled()){
            // If quiescence is not enabled, ignore these calls
            return;
        }
        if (blockFinalized) {
            throw new IllegalStateException("Block already finalized");
        }
        maxConsensusTime = CompareTo.max(maxConsensusTime, newConsensusTime);
    }

    public void finishedHandlingTransactions() {
        if(controller.isDisabled()){
            // If quiescence is not enabled, ignore these calls
            return;
        }
        blockFinalized = true;
        controller.blockFinalized(this);
    }

    public long getBlockNumber() {
        return blockNumber;
    }

    public long getRelevantTransactionCount() {
        return relevantTransactionCount;
    }

    @NonNull
    public Instant getMaxConsensusTime() {
        return maxConsensusTime;
    }
}
