// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_2;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;

/**
 * Metrics to track the state lifecycle events
 */
public class StateLifecycleMetrics {

    private final RunningAverageMetric avgStateCopyMicros;

    private static final RunningAverageMetric.Config AVG_STATE_COPY_MICROS_CONFIG = new RunningAverageMetric.Config(
                    INTERNAL_CATEGORY, "stateCopyMicros")
            .withDescription(
                    "average time it takes the State.copy() method in StateLifecycles to finish " + "(in microseconds)")
            .withFormat(FORMAT_16_2);

    public StateLifecycleMetrics(final Metrics metrics) {
        avgStateCopyMicros = metrics.getOrCreate(AVG_STATE_COPY_MICROS_CONFIG);
    }

    /**
     * Records the time it takes {@link State#copy()} to finish (in microseconds)
     *
     * @param micros
     * 		the amount of time in microseconds
     */
    public void stateCopyMicros(final double micros) {
        avgStateCopyMicros.update(micros);
    }
}
