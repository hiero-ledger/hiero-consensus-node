// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction;

import static org.hiero.base.CompareTo.isLessThan;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.TimestampedTransaction;

/**
 * Store a list of transactions created by self, both system and non-system, for wrapping in the next event to be
 * created.
 */
public class TransactionPoolNexus implements EventTransactionSupplier {
    private static final Logger logger = LogManager.getLogger(TransactionPoolNexus.class);
    private static final Duration MINIMUM_REJECTION_LOG_PERIOD = Duration.ofMinutes(1);

    /**
     * The default maximum amount of time the platform may be in an unhealthy state before we start rejecting
     * transactions.
     */
    public static final Duration DEFAULT_MAXIMUM_PERMISSIBLE_UNHEALTHY_DURATION = Duration.ofSeconds(1);

    private enum ApplicationTransactionRejectionReason {
        PLATFORM_UNHEALTHY,
        PLATFORM_NOT_ACTIVE,
        PLATFORM_UNHEALTHY_AND_NOT_ACTIVE,
        NULL_TRANSACTION,
        TRANSACTION_TOO_LARGE,
        QUEUE_FULL
    }

    /**
     * The maximum amount of time the platform may be in an unhealthy state before we start rejecting transactions.
     */
    private final Duration maximumPermissibleUnhealthyDuration;

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
     * The most recent unhealthy duration reported by the health monitor.
     */
    private Duration lastReportedUnhealthyDuration = Duration.ZERO;

    /**
     * Time source for timestamping transactions.
     */
    private final InstantSource time;

    /**
     * Last log time for each rejection reason, to avoid flooding logs while preserving signal.
     */
    private final EnumMap<ApplicationTransactionRejectionReason, Instant> lastRejectionLogTimes =
            new EnumMap<>(ApplicationTransactionRejectionReason.class);

    /**
     * Creates a new transaction pool for transactions waiting to be put in an event.
     *
     * @param transactionLimits                     the configuration to use
     * @param throttleTransactionQueueSize          the maximum number of transactions that can be buffered before new
     *                                              application transactions are rejected
     * @param maximumPermissibleUnhealthyDuration   the maximum duration the platform may be unhealthy before rejecting
     *                                              transactions
     * @param metrics                               the metrics to use
     * @param time                                  the time source for timestamping transactions
     */
    public TransactionPoolNexus(
            @NonNull final TransactionLimits transactionLimits,
            final int throttleTransactionQueueSize,
            @NonNull final Duration maximumPermissibleUnhealthyDuration,
            @NonNull final Metrics metrics,
            @NonNull final InstantSource time) {
        maxTransactionBytesPerEvent = transactionLimits.maxTransactionBytesPerEvent();
        this.throttleTransactionQueueSize = throttleTransactionQueueSize;
        this.maximumPermissibleUnhealthyDuration =
                Objects.requireNonNull(maximumPermissibleUnhealthyDuration, "maximumPermissibleUnhealthyDuration");
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
        final ApplicationTransactionRejectionReason rejectionReason =
                getApplicationTransactionRejectionReason(appTransaction);
        if (rejectionReason != null) {
            logRejectedApplicationTransaction(rejectionReason, appTransaction);
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
        if (!priority && getBufferedTransactionQueueDepth() > throttleTransactionQueueSize) {
            transactionPoolMetrics.recordRejectedAppTransaction();
            logRejectedApplicationTransaction(ApplicationTransactionRejectionReason.QUEUE_FULL, transaction);
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
        Objects.requireNonNull(duration);
        lastReportedUnhealthyDuration = duration;
        final boolean wasHealthy = healthy;
        healthy = isLessThan(duration, maximumPermissibleUnhealthyDuration);
        if (wasHealthy == healthy) {
            return;
        }

        if (healthy) {
            logger.info(
                    "Transaction pool became healthy and will accept application transactions again "
                            + "[reportedUnhealthyDuration={}, maximumPermissibleUnhealthyDuration={}, "
                            + "platformStatus={}, queueDepth={}]",
                    duration,
                    maximumPermissibleUnhealthyDuration,
                    platformStatus,
                    getBufferedTransactionQueueDepth());
        } else {
            logger.warn(
                    "Transaction pool became unhealthy and will reject application transactions "
                            + "[reportedUnhealthyDuration={}, maximumPermissibleUnhealthyDuration={}, "
                            + "platformStatus={}, queueDepth={}]",
                    duration,
                    maximumPermissibleUnhealthyDuration,
                    platformStatus,
                    getBufferedTransactionQueueDepth());
        }
    }

    /**
     * Clear all the transactions
     */
    synchronized void clear() {
        bufferedTransactions.clear();
        priorityBufferedTransactions.clear();
        bufferedSignatureTransactionCount = 0;
    }

    @Nullable
    private ApplicationTransactionRejectionReason getApplicationTransactionRejectionReason(
            @Nullable final Bytes transaction) {
        if (!healthy && platformStatus != PlatformStatus.ACTIVE) {
            return ApplicationTransactionRejectionReason.PLATFORM_UNHEALTHY_AND_NOT_ACTIVE;
        }
        if (!healthy) {
            return ApplicationTransactionRejectionReason.PLATFORM_UNHEALTHY;
        }
        if (platformStatus != PlatformStatus.ACTIVE) {
            return ApplicationTransactionRejectionReason.PLATFORM_NOT_ACTIVE;
        }
        if (transaction == null) {
            return ApplicationTransactionRejectionReason.NULL_TRANSACTION;
        }
        if (transaction.length() > maximumTransactionSize) {
            return ApplicationTransactionRejectionReason.TRANSACTION_TOO_LARGE;
        }
        return null;
    }

    private void logRejectedApplicationTransaction(
            @NonNull final ApplicationTransactionRejectionReason rejectionReason, @Nullable final Bytes transaction) {
        final Instant now = time.instant();
        final Instant lastLogTime = lastRejectionLogTimes.get(rejectionReason);
        if (lastLogTime != null && isLessThan(Duration.between(lastLogTime, now), MINIMUM_REJECTION_LOG_PERIOD)) {
            return;
        }
        lastRejectionLogTimes.put(rejectionReason, now);

        final Long transactionSize = transaction == null ? null : transaction.length();
        final int queueDepth = getBufferedTransactionQueueDepth();
        switch (rejectionReason) {
            case PLATFORM_UNHEALTHY -> logger.warn(
                    "Rejected application transaction because the platform health gate is closed "
                            + "[reportedUnhealthyDuration={}, maximumPermissibleUnhealthyDuration={}, "
                            + "platformStatus={}, queueDepth={}, throttleTransactionQueueSize={}, "
                            + "transactionSize={}, maximumTransactionSize={}]",
                    lastReportedUnhealthyDuration,
                    maximumPermissibleUnhealthyDuration,
                    platformStatus,
                    queueDepth,
                    throttleTransactionQueueSize,
                    transactionSize,
                    maximumTransactionSize);
            case PLATFORM_NOT_ACTIVE -> logger.warn(
                    "Rejected application transaction because the transaction pool platform status is not ACTIVE "
                            + "[platformStatus={}, healthy={}, reportedUnhealthyDuration={}, "
                            + "maximumPermissibleUnhealthyDuration={}, queueDepth={}, "
                            + "throttleTransactionQueueSize={}, transactionSize={}, maximumTransactionSize={}]",
                    platformStatus,
                    healthy,
                    lastReportedUnhealthyDuration,
                    maximumPermissibleUnhealthyDuration,
                    queueDepth,
                    throttleTransactionQueueSize,
                    transactionSize,
                    maximumTransactionSize);
            case PLATFORM_UNHEALTHY_AND_NOT_ACTIVE -> logger.warn(
                    "Rejected application transaction because the platform is both unhealthy and not ACTIVE "
                            + "[platformStatus={}, reportedUnhealthyDuration={}, "
                            + "maximumPermissibleUnhealthyDuration={}, queueDepth={}, "
                            + "throttleTransactionQueueSize={}, transactionSize={}, maximumTransactionSize={}]",
                    platformStatus,
                    lastReportedUnhealthyDuration,
                    maximumPermissibleUnhealthyDuration,
                    queueDepth,
                    throttleTransactionQueueSize,
                    transactionSize,
                    maximumTransactionSize);
            case NULL_TRANSACTION -> logger.warn(
                    "Rejected application transaction because the transaction payload was null "
                            + "[platformStatus={}, healthy={}, reportedUnhealthyDuration={}, "
                            + "maximumPermissibleUnhealthyDuration={}, queueDepth={}, "
                            + "throttleTransactionQueueSize={}]",
                    platformStatus,
                    healthy,
                    lastReportedUnhealthyDuration,
                    maximumPermissibleUnhealthyDuration,
                    queueDepth,
                    throttleTransactionQueueSize);
            case TRANSACTION_TOO_LARGE -> logger.warn(
                    "Rejected application transaction because it exceeds the maximum transaction size "
                            + "[transactionSize={}, maximumTransactionSize={}, platformStatus={}, healthy={}, "
                            + "reportedUnhealthyDuration={}, queueDepth={}, throttleTransactionQueueSize={}]",
                    transactionSize,
                    maximumTransactionSize,
                    platformStatus,
                    healthy,
                    lastReportedUnhealthyDuration,
                    queueDepth,
                    throttleTransactionQueueSize);
            case QUEUE_FULL -> logger.warn(
                    "Rejected application transaction because the transaction queue is over capacity "
                            + "[queueDepth={}, throttleTransactionQueueSize={}, platformStatus={}, healthy={}, "
                            + "reportedUnhealthyDuration={}, maximumPermissibleUnhealthyDuration={}, "
                            + "transactionSize={}, maximumTransactionSize={}]",
                    queueDepth,
                    throttleTransactionQueueSize,
                    platformStatus,
                    healthy,
                    lastReportedUnhealthyDuration,
                    maximumPermissibleUnhealthyDuration,
                    transactionSize,
                    maximumTransactionSize);
        }
    }

    private int getBufferedTransactionQueueDepth() {
        return bufferedTransactions.size() + priorityBufferedTransactions.size();
    }
}
