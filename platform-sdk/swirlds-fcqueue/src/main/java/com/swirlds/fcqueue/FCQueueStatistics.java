// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;
import java.util.Objects;

/**
 * Singleton factory for loading and registering {@link FCQueue} statistics. This is the primary entry point for all
 * SwirldMain implementations that wish to track {@link FCQueue} statistics.
 */
public class FCQueueStatistics {

    public static final String FCQUEUE_CATEGORY = "FCQueue";

    /**
     * true if these statistics have been registered by the application; otherwise false
     */
    private static volatile boolean registered;

    /**
     * avg time taken to execute the FCQueue add method, including locks (in microseconds)
     */
    private static final RunningAverageMetric.Config FCQ_ADD_EXECUTION_MICROS_CONFIG = new RunningAverageMetric.Config(
                    FCQUEUE_CATEGORY, "fcqAddExecMicroSec")
            .withDescription("avg time taken to execute the FCQueue add method, not including locks (in microseconds)")
            .withFormat(FloatFormats.FORMAT_9_6);

    private static RunningAverageMetric fcqAddExecutionMicros;

    /**
     * avg time taken to execute the FCQueue remove method, including locks (in microseconds)
     */
    private static final RunningAverageMetric.Config FCQ_REMOVE_EXECUTION_MICROS_CONFIG =
            new RunningAverageMetric.Config(FCQUEUE_CATEGORY, "fcqRemoveExecMicroSec")
                    .withDescription(
                            "avg time taken to execute the FCQueue remove method, not including locks (in microseconds)")
                    .withFormat(FloatFormats.FORMAT_9_6);

    private static RunningAverageMetric fcqRemoveExecutionMicros;

    /**
     * avg time taken to execute the FCQueue getHash method, including locks (in microseconds)
     */
    private static final RunningAverageMetric.Config FCQ_HASH_EXECUTION_MICROS_CONFIG = new RunningAverageMetric.Config(
                    FCQUEUE_CATEGORY, "fcqHashExecMicroSec")
            .withDescription(
                    "avg time taken to execute the FCQueue remove method, not including locks (in microseconds)")
            .withFormat(FloatFormats.FORMAT_9_6);

    private static RunningAverageMetric fcqHashExecutionMicros;

    /**
     * Default private constructor to ensure that this may not be instantiated.
     */
    private FCQueueStatistics() {}

    /**
     * Registers the {@link FCQueue} statistics with the specified Platform instance.
     *
     * @param metrics
     * 		the metrics-system
     * @throws NullPointerException in case {@code metrics} parameter is {@code null}
     */
    public static void register(final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        fcqAddExecutionMicros = metrics.getOrCreate(FCQ_ADD_EXECUTION_MICROS_CONFIG);
        fcqRemoveExecutionMicros = metrics.getOrCreate(FCQ_REMOVE_EXECUTION_MICROS_CONFIG);
        fcqHashExecutionMicros = metrics.getOrCreate(FCQ_HASH_EXECUTION_MICROS_CONFIG);

        registered = true;
    }

    /**
     * Gets a value indicating whether the SwirldMain has called the {@link
     * #register(Metrics)} method on this factory.
     *
     * @return true if these statistics have been registered by the application; otherwise false
     */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * Update the average time taken to execute the FCQueue add() method
     *
     * @param value the value to record
     */
    public static void updateFcqAddExecutionMicros(final double value) {
        if (fcqAddExecutionMicros != null) {
            fcqAddExecutionMicros.update(value);
        }
    }

    /**
     * Update the average time taken to execute the FCQueue remove() method
     *
     * @param value the value to record
     */
    public static void updateFcqRemoveExecutionMicros(final double value) {
        if (fcqRemoveExecutionMicros != null) {
            fcqRemoveExecutionMicros.update(value);
        }
    }

    /**
     * Update the average time taken to execute the FCQueue getHash() method
     *
     * @param value the value to record
     */
    public static void updateFcqHashExecutionMicros(final double value) {
        if (fcqHashExecutionMicros != null) {
            fcqHashExecutionMicros.update(value);
        }
    }
}
