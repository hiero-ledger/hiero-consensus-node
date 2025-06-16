// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Metrics collection for transaction duration tracking.
 * Tracks the running average ops duration of transactions in nanoseconds.
 */
@Singleton
public class OpsDurationPerTransactionMetrics {
    private static final RunningAverageMetric.Config RUNNING_AVG_DURATION_METRIC_CONFIG =
            new RunningAverageMetric.Config("transaction", "ops_duration_running_avg")
                    .withDescription("A running average ops duration (i.e. a synthetic duration "
                            + "based on predefined OpCode timing) of EVM transactions.");

    private final RunningAverageMetric metric;

    @Inject
    public OpsDurationPerTransactionMetrics(@NonNull final Metrics metrics) {
        this.metric = requireNonNull(metrics).getOrCreate(RUNNING_AVG_DURATION_METRIC_CONFIG);
    }

    /**
     * Records the duration of a transaction in nanoseconds
     *
     * @param opsDurationNanos the ops duration in nanoseconds
     */
    public void recordTxnTotalOpsDuration(final long opsDurationNanos) {
        this.metric.update(opsDurationNanos);
    }

    /**
     * Gets the current running average ops duration.
     *
     * @return the ops duration in nanoseconds
     */
    public double get() {
        return metric.get();
    }
}
