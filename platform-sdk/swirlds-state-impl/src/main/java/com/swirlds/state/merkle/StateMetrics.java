// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_2;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.metrics.api.Metrics;
import org.hiero.consensus.metrics.RunningAverageMetric;

/**
 * Collection of metrics related to the state lifecycle
 */
class StateMetrics {

    private static final RunningAverageMetric.Config AVG_STATE_COPY_MICROS_CONFIG = new RunningAverageMetric.Config(
                    INTERNAL_CATEGORY, "stateCopyMicros")
            .withDescription("average time it takes the State.copy() method in ConsensusStateEventHandler to finish "
                    + "(in microseconds)")
            .withFormat(FORMAT_16_2);
    private final RunningAverageMetric avgStateCopyMicros;

    /**
     * Constructor of {@code StateMetrics}
     *
     * @param metrics
     * 		a reference to the metrics-system
     * @throws IllegalArgumentException
     * 		if {@code metrics} is {@code null}
     */
    public StateMetrics(final Metrics metrics) {

        avgStateCopyMicros = metrics.getOrCreate(AVG_STATE_COPY_MICROS_CONFIG);
    }

    /**
     * Records the time it takes {@link StateLifecycleManagerImpl#copyMutableState()} to finish (in microseconds)
     *
     * @param micros
     * 		the amount of time in microseconds
     */
    public void stateCopyMicros(final double micros) {
        avgStateCopyMicros.update(micros);
    }
}
