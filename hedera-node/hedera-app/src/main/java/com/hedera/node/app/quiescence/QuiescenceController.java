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
    public @NonNull QuiescenceBlockTracker startingBlock(final long blockNumber) {
        // This should be called from HandleWorkflow when starting to handle a new block
        // This should return an object even if quiescence is disabled, so that the caller does not need to check
        // if quiescence is enabled or not. We will later ignore the object if quiescence is disabled.
        return new QuiescenceBlockTracker(blockNumber, this);
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
