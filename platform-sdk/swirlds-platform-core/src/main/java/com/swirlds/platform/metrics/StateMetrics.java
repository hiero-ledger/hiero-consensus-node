// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_6;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_9_6;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.PlatformStatNames;
import com.swirlds.state.State;

/**
 * Collection of metrics related to the state lifecycle
 */
public class StateMetrics {

    private static final RunningAverageMetric.Config AVG_SEC_TRANS_HANDLED_CONFIG = new RunningAverageMetric.Config(
                    INTERNAL_CATEGORY, "secTransH")
            .withDescription(
                    "avg time to handle a consensus transaction in ConsensusStateEventHandler.onHandleTransaction "
                            + "(in seconds)")
            .withFormat(FORMAT_10_6);
    private final RunningAverageMetric avgSecTransHandled;

    private static final RunningAverageMetric.Config AVG_CONS_HANDLE_TIME_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "SecC2H")
            .withDescription("time from knowing consensus for a transaction to handling it (in seconds)")
            .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgConsHandleTime;

    private static final SpeedometerMetric.Config TRANS_HANDLED_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    INTERNAL_CATEGORY, PlatformStatNames.TRANSACTIONS_HANDLED_PER_SECOND)
            .withDescription("number of consensus transactions per second handled "
                    + "by ConsensusStateEventHandler.onHandleTransaction()")
            .withFormat(FORMAT_9_6);
    private final SpeedometerMetric transHandledPerSecond;

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
        avgSecTransHandled = metrics.getOrCreate(AVG_SEC_TRANS_HANDLED_CONFIG);
        avgConsHandleTime = metrics.getOrCreate(AVG_CONS_HANDLE_TIME_CONFIG);
        transHandledPerSecond = metrics.getOrCreate(TRANS_HANDLED_PER_SECOND_CONFIG);
        avgStateCopyMicros = metrics.getOrCreate(AVG_STATE_COPY_MICROS_CONFIG);
    }

    /**
     * Records the amount of time to handle a consensus transaction in {@link State}.
     *
     * @param seconds
     * 		the amount of time in seconds
     */
    public void consensusTransHandleTime(final double seconds) {
        avgSecTransHandled.update(seconds);
    }

    /**
     * Records the amount of time between a transaction reaching consensus and being handled in {@link State}.
     *
     * @param seconds
     * 		the amount of time in seconds
     */
    public void consensusToHandleTime(final double seconds) {
        avgConsHandleTime.update(seconds);
    }

    /**
     * Records the fact that consensus transactions were handled by {@link State}.
     */
    public void consensusTransHandled(final int numTrans) {
        transHandledPerSecond.update(numTrans);
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
