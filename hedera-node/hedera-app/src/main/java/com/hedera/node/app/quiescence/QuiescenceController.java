// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    }

    /**
     * Notifies the controller that a block has been fully signed.
     *
     * @param block the fully signed block
     */
    public void fullySignedBlock(@NonNull final Block block) {
        //TODO in HandleWorkflow:540 we should get the cons time & txn & block number
        // then we need another method for when a block is fully signed
        if (!config.enabled()) {
            return;
        }
        // FOR EXECUTION REVIEWERS:
        // Can we avoid parsing transactions here?
        final long transactionCount = block.items().stream()
                .filter(BlockItem::hasSignedTransaction)
                .map(BlockItem::signedTransaction)
                .filter(Objects::nonNull)
                //.filter(QuiescenceUtils::isRelevantTransaction)
                .count();
        final long updatedValue = pipelineTransactionCount.addAndGet(-transactionCount);
        if (updatedValue < 0) {
            logger.error("Quiescence transaction count overflow, turning off quiescence");
            disableQuiescence();
        }
        final Optional<Timestamp> maxConsensusTime = block.items().stream()
                .filter(BlockItem::hasStateChanges)
                .map(BlockItem::stateChanges)
                .filter(Objects::nonNull)
                .map(StateChanges::stateChanges)
                .flatMap(List::stream)
                .filter(StateChange::hasSingletonUpdate)
                .map(StateChange::singletonUpdate)
                .filter(Objects::nonNull)
                .filter(SingletonUpdateChange::hasBlockStreamInfoValue)
                .map(SingletonUpdateChange::blockStreamInfoValue)
                .map(BlockStreamInfo::lastHandleTime)
                .max(HapiUtils.TIMESTAMP_COMPARATOR);
        if (maxConsensusTime.isEmpty()) {
            return;
        }
        final Instant maxConsensusInstant = HapiUtils.asInstant(maxConsensusTime.get());
        nextTct.accumulateAndGet(maxConsensusInstant, QuiescenceController::tctUpdate);
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
            pipelineTransactionCount.addAndGet(countRelevantTransactions(transactions.iterator()));
        } catch (IllegalArgumentException e) {
            logger.error("Failed to count relevant transactions. Turning off quiescence.", e);
            disableQuiescence();
        }

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
        pipelineTransactionCount.addAndGet(-countRelevantTransactions(event.transactionIterator()));
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

    private static Instant tctUpdate(@NonNull final Instant currentTct, @NonNull final Instant currentConsensusTime) {
        // once consensus time passes the TCT, we want to return null to indicate that there is no TCT
        return currentConsensusTime.isAfter(currentTct) ? null : currentTct;
    }

    private void disableQuiescence() {
        // setting to a very high value to effectively disable quiescence
        // if set to Long.MAX_VALUE, it may overflow and become negative
        pipelineTransactionCount.set(Long.MAX_VALUE / 2);
    }

    public static long countRelevantTransactions(@NonNull final Iterator<Transaction> transactions) {
        long count = 0;
        while (transactions.hasNext()) {
            final Transaction transaction = transactions.next();
            final Object metadata = transaction.getMetadata();
            if (!(transaction.getMetadata() instanceof final PreHandleResult preHandleResult)) {
                throw new IllegalArgumentException("Failed to find PreHandleResult in transaction metadata (%s)"
                        .formatted(metadata));
            }
            if (QuiescenceUtils.isRelevantTransaction(preHandleResult.txInfo())) {
                count++;
            }
        }
        return count;
    }

}
