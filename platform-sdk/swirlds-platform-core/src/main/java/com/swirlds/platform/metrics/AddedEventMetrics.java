// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_13_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_0;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_17_1;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_5_3;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.stats.AverageStat;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Maintains all metrics which need to be updated on a new event
 */
public class AddedEventMetrics {

    private final NodeId selfId;

    private static final SpeedometerMetric.Config EVENTS_CREATED_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "cEvents_per_sec")
            .withDescription("number of events per second created by this node")
            .withFormat(FORMAT_16_2);
    private final SpeedometerMetric eventsCreatedPerSecond;

    private static final RunningAverageMetric.Config AVG_CREATED_RECEIVED_TIME_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "secC2R")
            .withDescription("time from another member creating an event to receiving it and "
                    + "verifying the signature (in seconds)")
            .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgCreatedReceivedTime;

    private static final SpeedometerMetric.Config EVENTS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "events_per_sec")
            .withDescription("number of unique events received per second (created by self and others)")
            .withFormat(FORMAT_16_2);
    private final SpeedometerMetric eventsPerSecond;

    private static final RunningAverageMetric.Config AVG_BYTES_PER_TRANSACTION_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "bytes_per_trans")
            .withDescription("number of bytes in each transactions")
            .withFormat(FORMAT_16_0);
    private final RunningAverageMetric avgBytesPerTransaction;

    private static final RunningAverageMetric.Config AVG_TRANSACTIONS_PER_EVENT_CONFIG =
            new RunningAverageMetric.Config(PLATFORM_CATEGORY, "trans_per_event")
                    .withDescription("number of app transactions in each event")
                    .withFormat(FORMAT_17_1);
    private final RunningAverageMetric avgTransactionsPerEvent;

    private static final String DETAILS = "(from unique events created by self and others)";
    private static final SpeedometerMetric.Config BYTES_PER_SECOND_TRANS_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "bytes_per_sec_trans")
            .withDescription("number of bytes in the transactions received per second " + DETAILS)
            .withFormat(FORMAT_16_2);
    private final SpeedometerMetric bytesPerSecondTrans;

    private static final SpeedometerMetric.Config TRANSACTIONS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "trans_per_sec")
            .withDescription("number of app transactions received per second " + DETAILS)
            .withFormat(FORMAT_13_2);
    private final SpeedometerMetric transactionsPerSecond;

    private static final SpeedometerMetric.Config TRANSACTIONS_PER_SECOND_SYS_CONFIG = new SpeedometerMetric.Config(
                    INTERNAL_CATEGORY, "trans_per_sec_sys")
            .withDescription("number of system transactions received per second " + DETAILS)
            .withFormat(FORMAT_13_2);

    private static final Counter.Config NUM_TRANS_CONFIG =
            new Counter.Config(INTERNAL_CATEGORY, "trans").withDescription("number of transactions received so far");
    private final Counter numTrans;

    private final AverageStat averageOtherParentAgeDiff;

    /**
     * The constructor of {@code AddedEventMetrics}
     *
     * @param selfId  the {@link NodeId} of this node
     * @param metrics a reference to the metrics-system
     */
    public AddedEventMetrics(final NodeId selfId, final Metrics metrics) {
        this.selfId = Objects.requireNonNull(selfId, "selfId must not be null");
        Objects.requireNonNull(metrics, "metrics must not be null");

        eventsCreatedPerSecond = metrics.getOrCreate(EVENTS_CREATED_PER_SECOND_CONFIG);
        averageOtherParentAgeDiff = new AverageStat(
                metrics,
                PLATFORM_CATEGORY,
                "opAgeDiff",
                "average age difference (in birth rounds) between an event created by this node and its other parent",
                FORMAT_5_3,
                AverageStat.WEIGHT_VOLATILE);
        avgCreatedReceivedTime = metrics.getOrCreate(AVG_CREATED_RECEIVED_TIME_CONFIG);
        eventsPerSecond = metrics.getOrCreate(EVENTS_PER_SECOND_CONFIG);
        avgBytesPerTransaction = metrics.getOrCreate(AVG_BYTES_PER_TRANSACTION_CONFIG);
        avgTransactionsPerEvent = metrics.getOrCreate(AVG_TRANSACTIONS_PER_EVENT_CONFIG);
        bytesPerSecondTrans = metrics.getOrCreate(BYTES_PER_SECOND_TRANS_CONFIG);
        transactionsPerSecond = metrics.getOrCreate(TRANSACTIONS_PER_SECOND_CONFIG);
        numTrans = metrics.getOrCreate(NUM_TRANS_CONFIG);
    }

    /**
     * Update the metrics when a new event is added to the hashgraph
     *
     * @param event the event that was added
     */
    public void eventAdded(final EventImpl event) {
        if (Objects.equals(event.getCreatorId(), selfId)) {
            eventsCreatedPerSecond.cycle();
            if (!event.getBaseEvent().getOtherParents().isEmpty()) {
                averageOtherParentAgeDiff.update(event.getBirthRound()
                        - event.getBaseEvent().getOtherParents().stream()
                                .map(ed -> ed.eventDescriptor().birthRound())
                                .max(Long::compareTo)
                                .orElse(0L));
            }
        } else {
            avgCreatedReceivedTime.update(
                    event.getTimeCreated().until(event.getBaseEvent().getTimeReceived(), ChronoUnit.NANOS)
                            * NANOSECONDS_TO_SECONDS);
        }

        // count the unique events in the hashgraph
        eventsPerSecond.cycle();

        // record stats for all transactions in this event
        // we have already ensured this isn't a duplicate event, so record all the stats on it:

        // count the bytes in the transactions, and bytes per second, and transactions per event
        // for both app transactions and system transactions.
        // Handle system transactions
        long appSize = 0;
        int numAppTrans = 0;

        final Iterator<Transaction> iterator = event.getBaseEvent().transactionIterator();
        while (iterator.hasNext()) {
            final Transaction transaction = iterator.next();
            numAppTrans++;
            appSize += transaction.getSize();
            avgBytesPerTransaction.update(transaction.getSize());
        }
        avgTransactionsPerEvent.update(numAppTrans);
        bytesPerSecondTrans.update(appSize);
        // count each transaction within that event (this is like calling cycle() numTrans times)
        transactionsPerSecond.update(numAppTrans);

        // count all transactions ever in the hashgraph
        if (event.getBaseEvent().getTransactionCount() != 0) {
            numTrans.add(event.getBaseEvent().getTransactionCount());
        }
    }
}
