// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

/**
 * Metrics collection for transaction duration tracking.
 * Tracks the running average ops duration of transactions in nanoseconds,
 * the total ops duration, and the count of transactions processed.
 */
@Singleton
public class OpsDurationPerTransactionMetrics {
    private static final RunningAverageMetric.Config RUNNING_AVG_TOTAL_TXN_DURATION_METRIC_CONFIG =
            new RunningAverageMetric.Config("transaction", "ops_duration_running_avg")
                    .withDescription("A running average ops duration (i.e. a synthetic duration "
                            + "based on predefined OpCode timing) of EVM transactions.");
    private static final Counter.Config COUNTER_TOTAL_TXN_DURATION_METRIC_CONFIG = new Counter.Config(
                    "transaction", "ops_duration_count")
            .withDescription("A count of the number of EVM transactions processed.");
    private static final LongAccumulator.Config ACCUMULATOR_TOTAL_TXN_DURATION_METRIC_CONFIG =
            new LongAccumulator.Config("transaction", "ops_duration_total_ns")
                    .withDescription("A total ops duration (i.e. a synthetic duration based on "
                            + "predefined OpCode timing) of EVM transactions in nanoseconds.")
                    .withAccumulator(Long::sum);

    private final RunningAverageMetric averageMetric;
    private final Counter counterMetric;
    private final LongAccumulator accumulatorMetric;

    public OpsDurationPerTransactionMetrics(@NonNull final Metrics metrics) {
        requireNonNull(metrics);
        this.averageMetric = metrics.getOrCreate(RUNNING_AVG_TOTAL_TXN_DURATION_METRIC_CONFIG);
        this.counterMetric = metrics.getOrCreate(COUNTER_TOTAL_TXN_DURATION_METRIC_CONFIG);
        this.accumulatorMetric = metrics.getOrCreate(ACCUMULATOR_TOTAL_TXN_DURATION_METRIC_CONFIG);
    }

    /**
     * Records the duration of a transaction in nanoseconds
     *
     * @param opsDurationNanos the ops duration in nanoseconds
     */
    public void recordTxnTotalOpsDuration(final long opsDurationNanos) {
        this.averageMetric.update(opsDurationNanos);
        this.counterMetric.increment();
        this.accumulatorMetric.update(opsDurationNanos);
    }

    /**
     * Gets the current running average ops duration.
     *
     * @return the ops duration in nanoseconds
     */
    public double getAverage() {
        return averageMetric.get();
    }

    /**
     * Gets the total ops duration in nanoseconds.
     *
     * @return the total ops duration in nanoseconds
     */
    public long getTotalOpsDuration() {
        return accumulatorMetric.get();
    }

    /**
     * Gets the count of transactions processed.
     *
     * @return the count of transactions processed
     */
    public long getCount() {
        return counterMetric.get();
    }
}
