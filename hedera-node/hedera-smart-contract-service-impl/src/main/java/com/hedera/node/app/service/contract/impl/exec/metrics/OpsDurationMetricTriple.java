// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongAccumulator;

/**
 * Represents a triple of metrics related to operation durations.
 * @param averageMetric
 * @param counter
 * @param accumulator
 */
public record OpsDurationMetricTriple(
        RunningAverageMetric averageMetric, Counter counter, LongAccumulator accumulator) {

    // Batch update of metrics for a single operation
    public void update(final long durationNanos) {
        averageMetric.update(durationNanos);
        counter.increment();
        accumulator.update(durationNanos);
    }
}
