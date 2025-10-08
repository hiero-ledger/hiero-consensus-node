// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Tracks all the information needed to determine if the system is quiescent or not.
 */
public class QuiescenceController {
    private static final Logger logger = LogManager.getLogger(QuiescenceController.class);

    private final QuiescenceConfig config;
    private final InstantSource time;
    private final LongSupplier pendingTransactionCount;

    private final AtomicReference<Instant> nextTct;
    private final AtomicLong pipelineTransactionCount;
    private final Map<Long, QuiescenceBlockTracker> blockTrackers;

    /**
     * Constructs a new quiescence controller.
     *
     * @param config                  the quiescence configuration
     * @param time                    the time source
     * @param pendingTransactionCount a supplier that provides the number of transactions submitted to the node but not
     *                                yet included put into an event
     */
    public QuiescenceController(
            final QuiescenceConfig config,
            final InstantSource time,
            final LongSupplier pendingTransactionCount) {
        this.config = Objects.requireNonNull(config);
        this.time = Objects.requireNonNull(time);
        this.pendingTransactionCount = Objects.requireNonNull(pendingTransactionCount);
        nextTct = new AtomicReference<>();
        pipelineTransactionCount = new AtomicLong(0);
        blockTrackers = new ConcurrentHashMap<>();
    }

    /**
     * Notifies the controller that a list of transactions have been sent to be pre-handled. There transactions will be
     * handled soon or will become stale.
     *
     * @param transactions the transactions are being pre-handled
     */
    public void onPreHandle(@NonNull final List<Transaction> transactions) {
        // Should be called at the end of Hedera.onPreHandle() when all transactions have been parsed
        if (!config.enabled()) {
            return;
        }
        try {
            pipelineTransactionCount.addAndGet(QuiescenceUtils.countRelevantTransactions(transactions.iterator()));
        } catch (final BadMetadataException e) {
            disableQuiescence(e);
        }

    }

    public QuiescenceBlockTracker startingBlock(final long blockNumber){
        //TODO in HandleWorkflow:540 we should get the cons time & txn & block number
        // then we need another method for when a block is fully signed
        return new QuiescenceBlockTracker(blockNumber, this);
    }

    void blockFinalized(@NonNull final QuiescenceBlockTracker blockTracker){
        if(!config.enabled()){
            // If quiescence is not enabled, ignore these calls
            return;
        }
        final QuiescenceBlockTracker prevValue = blockTrackers.put(blockTracker.getBlockNumber(), blockTracker);
        if(prevValue != null){
            disableQuiescence("Block %d was already finalized".formatted(blockTracker.getBlockNumber()));
        }
    }

    /**
     * Notifies the controller that a block has been fully signed.
     *
     * @param blockNumber the fully signed block number
     */
    public void blockFullySigned(final long blockNumber){
        final QuiescenceBlockTracker blockTracker = blockTrackers.remove(blockNumber);
        if(blockTracker == null){
            disableQuiescence("Cannot find block tracker for block %d".formatted(blockNumber));
            return;
        }
        updateTransactionCount(-blockTracker.getRelevantTransactionCount());
        nextTct.accumulateAndGet(blockTracker.getMaxConsensusTime(), QuiescenceController::tctUpdate);
    }

    /**
     * Notifies the controller that an event has become stale and will not be handled.
     *
     * @param event the event that has become stale
     */
    public void staleEvent(@NonNull final Event event) {
        if (!config.enabled()) {
            return;
        }
        try {
            pipelineTransactionCount.addAndGet(-QuiescenceUtils.countRelevantTransactions(event.transactionIterator()));
        } catch (final BadMetadataException e) {
            disableQuiescence("Failed to count relevant transactions in staleEvent()");
        }
    }

    /**
     * Notifies the controller of the next target consensus time.
     *
     * @param targetConsensusTime the next target consensus time
     */
    public void setNextTargetConsensusTime(@Nullable final Instant targetConsensusTime) {
        if (!config.enabled()) {
            return;
        }
        nextTct.set(targetConsensusTime);
    }

    /**
     * Notifies the controller that the platform status has changed.
     *
     * @param platformStatus the new platform status
     */
    public void platformStatusUpdate(@NonNull final PlatformStatus platformStatus) {
        if (platformStatus == PlatformStatus.RECONNECT_COMPLETE) {
            pipelineTransactionCount.set(0);
        }
    }

    /**
     * Returns the current quiescence command.
     *
     * @return the current quiescence command
     */
    public QuiescenceCommand getQuiescenceStatus() {
        if (!config.enabled()) {
            return QuiescenceCommand.DONT_QUIESCE;
        }
        if (pipelineTransactionCount.get() > 0) {
            return QuiescenceCommand.DONT_QUIESCE;
        }
        final Instant tct = nextTct.get();
        if (tct != null && tct.minus(config.tctDuration()).isBefore(time.instant())) {
            return QuiescenceCommand.DONT_QUIESCE;
        }
        if (pendingTransactionCount.getAsLong() > 0) {
            return QuiescenceCommand.BREAK_QUIESCENCE;
        }
        return QuiescenceCommand.QUIESCE;
    }

    private static Instant tctUpdate(@Nullable final Instant currentTct, @NonNull final Instant currentConsensusTime) {
        if (currentTct == null) {
            return null;
        }
        // once consensus time passes the TCT, we want to return null to indicate that there is no TCT
        return currentConsensusTime.isAfter(currentTct) ? null : currentTct;
    }

    private void updateTransactionCount(final long delta) {
        final long updatedValue = pipelineTransactionCount.addAndGet(delta);
        if (updatedValue < 0) {
            disableQuiescence("Quiescence transaction count is negative, this indicates a bug");
        }
    }
    void disableQuiescence(@NonNull final String reason) {
        disableQuiescence();
        logger.error("Disabling quiescence, reason: {}", reason);

    }

    void disableQuiescence(@NonNull final Exception exception) {
        disableQuiescence();
        logger.error("Disabling quiescence due to exception:", exception);
    }

    private void disableQuiescence() {
        // setting to a very high value to effectively disable quiescence
        // if set to Long.MAX_VALUE, it may overflow and become negative
        pipelineTransactionCount.set(Long.MAX_VALUE / 2);
    }

    boolean isDisabled(){
        //TODO expand isEnabled with message and pipeline check
        return !config.enabled();
    }

}
