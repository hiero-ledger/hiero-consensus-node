// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.config.data.QuiescenceConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Tracks all the information needed to determine if the system is quiescent or not. This class is thread-safe, it is
 * expected that all methods may be called concurrently from different threads.
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
     * If set, the block tracker for the in-progress block.
     */
    @Nullable
    private QuiescenceBlockTracker inProgressBlockTracker;

    /**
     * Constructs a new quiescence controller.
     *
     * @param config                  the quiescence configuration
     * @param time                    the time source
     * @param pendingTransactionCount a supplier that provides the number of transactions submitted to the node but not
     *                                yet included put into an event
     */
    public QuiescenceController(
            @NonNull final QuiescenceConfig config,
            @NonNull final InstantSource time,
            @NonNull final LongSupplier pendingTransactionCount) {
        this.config = requireNonNull(config);
        this.time = requireNonNull(time);
        this.pendingTransactionCount = requireNonNull(pendingTransactionCount);
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
        if (isDisabled()) {
            return;
        }
        try {
            pipelineTransactionCount.addAndGet(QuiescenceUtils.countRelevantTransactions(transactions.iterator()));
        } catch (final BadMetadataException e) {
            disableQuiescence(e);
        }
    }

    /**
     * This method should be called when starting to handle a new block. It returns a block tracker that should be
     * updated with transactions and consensus time and then notified when the block is finalized. Although this class
     * is thread-safe, the returned block tracker is not thread-safe and should only be used from a single thread.
     *
     * @param blockNumber the block number being started
     * @return the block tracker for the new block
     */
    public @Nullable QuiescenceBlockTracker startingBlock(final long blockNumber) {
        if (isDisabled()) {
            return null;
        }
        inProgressBlockTracker = new QuiescenceBlockTracker(blockNumber, this);
        return inProgressBlockTracker;
    }

    /**
     * Notifies the controller that the in-progress block has finished handling all transactions.
     * This method should be called after all transactions in the current block have been processed
     * and the block is ready to be finalized.
     * <p>
     * This method delegates to the in-progress block tracker's {@code finishedHandlingTransactions()}
     * method. If quiescence is disabled or if an exception occurs during the operation, quiescence
     * will be disabled.
     * <p>
     * Note: This method expects that {@link #startingBlock(long)} has been called previously to
     * initialize the in-progress block tracker.
     *
     * @throws NullPointerException if no in-progress block tracker exists (wrapped and handled internally)
     */
    public void finishHandlingInProgressBlock() {
        if (isDisabled()) {
            return;
        }
        try {
            requireNonNull(inProgressBlockTracker).finishedHandlingTransactions();
        } catch (Exception e) {
            disableQuiescence(e);
        }
    }

    /**
     * Notifies the controller that a consensus transaction has been processed in the in-progress block.
     * This method should be called for each transaction as it is handled within the current block.
     * <p>
     * This method performs two operations:
     * <ol>
     *   <li>Records the transaction in the in-progress block tracker via {@code blockTransaction()}</li>
     *   <li>Updates the consensus time tracker with the transaction's consensus timestamp via
     *       {@code consensusTimeAdvanced()}</li>
     * </ol>
     * <p>
     * If quiescence is disabled or if an exception occurs during the operation, quiescence will be disabled.
     * <p>
     * Note: This method expects that {@link #startingBlock(long)} has been called previously to
     * initialize the in-progress block tracker.
     *
     * @param txn the consensus transaction that has been processed in the block
     * @throws NullPointerException if no in-progress block tracker exists (wrapped and handled internally)
     */
    public void inProgressBlockTransaction(@NonNull final ConsensusTransaction txn) {
        if (isDisabled()) {
            return;
        }
        try {
            requireNonNull(inProgressBlockTracker).blockTransaction(txn);
            inProgressBlockTracker.consensusTimeAdvanced(txn.getConsensusTimestamp());
        } catch (final Exception e) {
            disableQuiescence(e);
        }
    }

    /**
     * If there is a block in progress, switches the block tracker, synchronously marking the previous block as
     * having finished handling transactions.
     * <p>
     * Only used by the {@link BlockRecordManagerImpl}, whose concept of finality does not extend to achieving a
     * TSS signature.
     * @param blockNumber the block number being started
     * @return whether the previous block was being tracked
     */
    public boolean switchTracker(final long blockNumber) {
        if (isDisabled()) {
            return false;
        }
        final boolean finishedPrevious = inProgressBlockTracker != null;
        // Has side effect of setting inProgressBlockTracker
        startingBlock(blockNumber);
        return finishedPrevious;
    }

    /**
     * Called by a block tracker when the block has been finalized.
     *
     * @param blockTracker the block tracker that has been finalized
     */
    void blockFinalized(@NonNull final QuiescenceBlockTracker blockTracker) {
        if (isDisabled()) {
            return;
        }
        final QuiescenceBlockTracker prevValue = blockTrackers.put(blockTracker.getBlockNumber(), blockTracker);
        if (prevValue != null) {
            disableQuiescence("Block %d was already finalized".formatted(blockTracker.getBlockNumber()));
        }
    }

    /**
     * Notifies the controller that a block has been fully signed.
     *
     * @param blockNumber the fully signed block number
     */
    public void blockFullySigned(final long blockNumber) {
        final QuiescenceBlockTracker blockTracker = blockTrackers.remove(blockNumber);
        if (blockTracker == null) {
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
        if (isDisabled()) {
            return;
        }
        try {
            pipelineTransactionCount.addAndGet(-QuiescenceUtils.countRelevantTransactions(event.transactionIterator()));
        } catch (final BadMetadataException e) {
            disableQuiescence(e);
        }
    }

    /**
     * Notifies the controller of the next target consensus time.
     *
     * @param targetConsensusTime the next target consensus time
     */
    public void setNextTargetConsensusTime(@Nullable final Instant targetConsensusTime) {
        if (isDisabled()) {
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
        if (isDisabled()) {
            return;
        }
        if (platformStatus == PlatformStatus.RECONNECT_COMPLETE) {
            pipelineTransactionCount.set(0);
            blockTrackers.clear();
        }
    }

    /**
     * Returns the current quiescence command.
     *
     * @return the current quiescence command
     */
    public @NonNull QuiescenceCommand getQuiescenceStatus() {
        if (isDisabled()) {
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

    /**
     * Disables quiescence, logging the reason.
     *
     * @param reason the reason quiescence is being disabled
     */
    void disableQuiescence(@NonNull final String reason) {
        disableQuiescence();
        logger.error("Disabling quiescence, reason: {}", reason);
    }

    /**
     * Disables quiescence, logging the exception.
     *
     * @param exception the exception that caused quiescence to be disabled
     */
    void disableQuiescence(@NonNull final Exception exception) {
        disableQuiescence();
        logger.error("Disabling quiescence due to exception:", exception);
    }

    /**
     * Indicates if quiescence is disabled.
     *
     * @return true if quiescence is disabled, false otherwise
     */
    boolean isDisabled() {
        return !config.enabled() || pipelineTransactionCount.get() < 0;
    }

    private void disableQuiescence() {
        // During normal operation the count should never be negative, so we use that to indicate disabled.
        // We use Long.MIN_VALUE/2 to avoid any concurrent updates from overflowing and wrapping around to positive.
        pipelineTransactionCount.set(Long.MIN_VALUE / 2);
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
}
