// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

@Singleton
public class TransactionThrottledByOpsDurationMetric {
    private static final Counter.Config CONFIG = new Counter.Config(
                    ContractMetrics.METRIC_CATEGORY,
                    String.format("%s:OpsDuration_TxnsThrottled", ContractMetrics.METRIC_SERVICE))
            .withDescription("Count of transactions throttled by ops duration limit");
    private final Counter metrics;

    public TransactionThrottledByOpsDurationMetric(@NonNull final Metrics metrics) {
        requireNonNull(metrics);
        this.metrics = metrics.getOrCreate(CONFIG);
    }

    /**
     * Increments the count of transactions throttled by ops duration limit.
     */
    public void increment() {
        metrics.increment();
    }

    /**
     * Gets the current count of transactions throttled by ops duration limit.
     *
     * @return the count of throttled transactions
     */
    public long getCount() {
        return metrics.get();
    }
}
