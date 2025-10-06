// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction;

import static org.hiero.base.CompareTo.isLessThan;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.TimestampedTransaction;

/**
 * Store a list of transactions created by self, both system and non-system, for wrapping in the next event to be
 * created.
 */
public class TransactionPoolNexus implements EventTransactionSupplier {
    /**
     * The maximum amount of time the platform may be in an unhealthy state before we start rejecting transactions.
     */
    private static final Duration maximumPermissibleUnhealthyDuration = Duration.ofSeconds(1);

    /**
     * A list of timestamped transactions created by this node waiting to be put into a self-event.
     */
    private final Queue<TimestampedTransaction> bufferedTransactions = new LinkedList<>();

    /**
     * A list of high-priority timestamped transactions created by this node waiting to be put into a self-event.
     * Transactions in this queue are always inserted into an event before transactions waiting in {@link #bufferedTransactions}.
     */
    private final Queue<TimestampedTransaction> priorityBufferedTransactions = new LinkedList<>();

    /**
     * The number of buffered signature transactions waiting to be put into events.
     */
    private int bufferedSignatureTransactionCount = 0;

    /**
     * The maximum number of bytes of transactions that can be put in an event.
     */
    private final int maxTransactionBytesPerEvent;

    /**
     * The maximum desired size of the transaction queue. If the queue is larger than this, then new app transactions
     * are rejected.
     */
    private final int throttleTransactionQueueSize;

    /**
     * Metrics for the transaction pool.
     */
    private final TransactionPoolMetrics transactionPoolMetrics;

    /**
     * The maximum size of a transaction in bytes.
     */
    private final int maximumTransactionSize;

    /**
     * The current status of the platform.
     */
    private PlatformStatus platformStatus = PlatformStatus.STARTING_UP;

    /**
     * Whether the platform is currently in a healthy state.
     */
    private boolean healthy = true;

    /**
     * Time source for timestamping transactions.
     */
    private final InstantSource time;

    /**
     * Creates a new transaction pool for transactions waiting to be put in an event.
     *
     * @param transactionLimits            the configuration to use
     * @param throttleTransactionQueueSize the maximum number of transactions that can be buffered before new
     *                                     application transactions are rejected
     * @param metrics                      the metrics to use
     * @param time                         the time source for timestamping transactions
     */
    public TransactionPoolNexus(
            @NonNull final TransactionLimits transactionLimits,
            final int throttleTransactionQueueSize,
            @NonNull final Metrics metrics,
            @NonNull final InstantSource time) {
        maxTransactionBytesPerEvent = transactionLimits.maxTransactionBytesPerEvent();
        this.throttleTransactionQueueSize = throttleTransactionQueueSize;
        this.time = Objects.requireNonNull(time, "time must not be null");

        transactionPoolMetrics = new TransactionPoolMetrics(
                metrics, this::getBufferedTransactionCount, this::getPriorityBufferedTransactionCount);

        maximumTransactionSize = transactionLimits.transactionMaxBytes();
    }

    // FUTURE WORK: these checks should be unified with the checks performed when a system transaction is submitted.
    // The reason why this method coexists with submitTransaction() is due to legacy reasons, not because it
    // actually makes sense to have this distinction.

    /**
     * Attempt to submit an application transaction. Similar to
     * {@link #submitTransaction} but with extra safeguards.
     *
     * @param appTransaction the transaction to submit
     * @return true if the transaction passed all validity checks and was accepted by the consumer
     */
    public synchronized boolean submitApplicationTransaction(@NonNull final Bytes appTransaction) {
        if (!healthy || platformStatus != PlatformStatus.ACTIVE) {
            return false;
        }

        if (appTransaction == null) {
            // FUTURE WORK: This really should throw, but to avoid changing existing API this will be changed later.
            return false;
        }
        if (appTransaction.length() > maximumTransactionSize) {
            // FUTURE WORK: This really should throw, but to avoid changing existing API this will be changed later.
            return false;
        }

        return submitTransaction(appTransaction, false);
    }

    /**
     * Submit a transaction that is considered a priority transaction. This transaction will be submitted before other
     * waiting transactions that are not marked with the priority flag.
     *
     * @param transaction the transaction to submit
     */
    public synchronized void submitPriorityTransaction(@NonNull final Bytes transaction) {
        submitTransaction(transaction, true);
    }

    /**
     * Attempt to submit a transaction.
     *
     * @param transaction The transaction. It must have been created by self.
     * @param priority    if true, then this transaction will be submitted before other waiting transactions that are
     *                    not marked with the priority flag. Use with moderation, adding too many priority transactions
     *                    (i.e. thousands per second) may disrupt the ability of the platform to perform some core
     *                    functionalities.
     * @return true if successful
     */
    private synchronized boolean submitTransaction(@NonNull final Bytes transaction, final boolean priority) {
        Objects.requireNonNull(transaction);

        // Always submit system transactions. If it's not a system transaction, then only submit it if we
        // don't violate queue size capacity restrictions.
        if (!priority
                && (bufferedTransactions.size() + priorityBufferedTransactions.size()) > throttleTransactionQueueSize) {
            transactionPoolMetrics.recordRejectedAppTransaction();
            return false;
        }

        if (priority) {
            bufferedSignatureTransactionCount++;
            transactionPoolMetrics.recordSubmittedPlatformTransaction();
        } else {
            transactionPoolMetrics.recordAcceptedAppTransaction();
        }

        final TimestampedTransaction timestampedTransaction = new TimestampedTransaction(transaction, time.instant());

        if (priority) {
            priorityBufferedTransactions.add(timestampedTransaction);
        } else {
            bufferedTransactions.add(timestampedTransaction);
        }

        return true;
    }

    /**
     * Update the platform status.
     *
     * @param platformStatus the new platform status
     */
    public synchronized void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        this.platformStatus = platformStatus;
        if (platformStatus == PlatformStatus.BEHIND) {
            clear();
        }
    }

    /**
     * Get the next transaction that should be inserted into an event, or null if there is no available transaction.
     *
     * @param currentEventSize the current size in bytes of the event being constructed
     * @return the next timestamped transaction, or null if no transaction is available
     */
    @Nullable
    private TimestampedTransaction getNextTransaction(final long currentEventSize) {
        final long maxSize = maxTransactionBytesPerEvent - currentEventSize;

        if (maxSize <= 0) {
            // the event is at capacity
            return null;
        }

        if (!priorityBufferedTransactions.isEmpty()
                && priorityBufferedTransactions.peek().transaction().length() <= maxSize) {
            bufferedSignatureTransactionCount--;
            return priorityBufferedTransactions.poll();
        }

        if (!bufferedTransactions.isEmpty()
                && bufferedTransactions.peek().transaction().length() <= maxSize) {
            return bufferedTransactions.poll();
        }

        return null;
    }

    /**
     * Removes as many transactions from the list waiting to be in an event that can fit (FIFO ordering), and returns
     * them as timestamped transactions.
     */
    @NonNull
    @Override
    public synchronized List<TimestampedTransaction> getTransactionsForEvent() {
        // Early return due to no transactions waiting
        if (bufferedTransactions.isEmpty() && priorityBufferedTransactions.isEmpty()) {
            return Collections.emptyList();
        }

        final List<TimestampedTransaction> selectedTrans = new LinkedList<>();
        long currEventSize = 0;

        while (true) {
            final TimestampedTransaction timestampedTransaction = getNextTransaction(currEventSize);

            if (timestampedTransaction == null) {
                // No transaction of suitable size is available
                break;
            }

            currEventSize += timestampedTransaction.transaction().length();
            selectedTrans.add(timestampedTransaction);
        }

        return selectedTrans;
    }

    /**
     * Check if there are any buffered signature transactions waiting to be put into events.
     *
     * @return true if there are any buffered signature transactions
     */
    public synchronized boolean hasBufferedSignatureTransactions() {
        return bufferedSignatureTransactionCount > 0;
    }

    /**
     * get the number of buffered transactions
     *
     * @return the number of transactions
     */
    private synchronized int getBufferedTransactionCount() {
        return bufferedTransactions.size();
    }

    /**
     * get the number of priority buffered transactions
     *
     * @return the number of transactions
     */
    private synchronized int getPriorityBufferedTransactionCount() {
        return priorityBufferedTransactions.size();
    }

    /**
     * Report the amount of time that the system has been in an unhealthy state. Will receive a report of
     * {@link Duration#ZERO} when the system enters a healthy state.
     *
     * @param duration the amount of time that the system has been in an unhealthy state
     */
    public synchronized void reportUnhealthyDuration(@NonNull final Duration duration) {
        healthy = isLessThan(duration, maximumPermissibleUnhealthyDuration);
    }

    /**
     * Clear all the transactions
     */
    synchronized void clear() {
        bufferedTransactions.clear();
        priorityBufferedTransactions.clear();
        bufferedSignatureTransactionCount = 0;
    }
}
