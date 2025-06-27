// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import static com.swirlds.common.units.TimeUnit.UNIT_MICROSECONDS;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.time.IntegerEpochTime;
import com.swirlds.common.units.TimeUnit;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks performance metrics for systems that operate in phases.
 *
 * Please note that this class is NOT thread safe and will fail in non-deterministic manner if methods are called
 * from different threads in parallel (or even without ensuring proper visibility between the threads).
 *
 * @param <T> the type of the phase, must be an enum
 */
public class PhaseTimer<T extends Enum<T>> {

    private final IntegerEpochTime time;

    private final boolean fractionMetricsEnabled;
    private final boolean absoluteTimeMetricsEnabled;

    /**
     * Tracks the fraction of the time, out of 1.0, spent in each phase.
     */
    private final Map<T, FractionalTimer> fractionalTimers = new HashMap<>();

    /**
     * Tracks the average time spent in a phase before switching to another phase.
     */
    private final Map<T, RunningAverageMetric> absoluteTimeMetrics = new HashMap<>();

    private T activePhase;

    /**
     * The previous time as measured by {@link IntegerEpochTime#getMicroTime()}.
     */
    private long previousTime;

    private final TimeUnit absoluteTimeUnit;

    /**
     * Create a new {@link PhaseTimer} instance. Do not call this directly, use {@link PhaseTimerBuilder#build()}.
     *
     * @param builder the builder
     */
    PhaseTimer(@NonNull final PhaseTimerBuilder<T> builder) {
        this.time = new IntegerEpochTime(builder.getTime());

        fractionMetricsEnabled = builder.areFractionMetricsEnabled();
        absoluteTimeMetricsEnabled = builder.areAbsoluteTimeMetricsEnabled();

        if (fractionMetricsEnabled) {
            for (final T phase : builder.getPhases()) {
                fractionalTimers.put(phase, new StandardFractionalTimer(builder.getTime()));
            }
        }

        absoluteTimeUnit = builder.getAbsoluteUnit();

        registerMetrics(
                builder.getMetrics(),
                builder.getMetricsCategory(),
                builder.getMetricsNamePrefix(),
                builder.getPhases());

        activePhase = builder.getInitialPhase();
        previousTime = time.getMicroTime();

        if (fractionMetricsEnabled) {
            fractionalTimers.get(activePhase).activate();
        }
    }

    /**
     * Activate a phase. The currently active phase, if different from the provided phase, is deactivated. Has no effect
     * if the requested phase is already active.
     * <p>
     * It is not thread safe to call this method concurrently on different threads.
     *
     * @param phase the phase to activate
     */
    public void activatePhase(@NonNull final T phase) {
        if (phase.equals(activePhase)) {
            // We are still in the same phase, intentional no-op
            return;
        }

        final long now = time.getMicroTime();

        if (fractionMetricsEnabled) {
            fractionalTimers.get(activePhase).deactivate(now);
            fractionalTimers.get(phase).activate(now);
        }

        if (absoluteTimeMetricsEnabled) {
            final long elapsedMicros = now - previousTime;
            absoluteTimeMetrics.get(activePhase).update(UNIT_MICROSECONDS.convertTo(elapsedMicros, absoluteTimeUnit));
        }

        previousTime = now;
        activePhase = phase;
    }

    /**
     * Returns current phase which was last activated on this time
     * @return active phase
     */
    public T getActivePhase() {
        return activePhase;
    }

    /**
     * Build the metric name for the fraction of time spent in a given phase.
     *
     * @param metricsNamePrefix the base name of the metric
     * @param phase             the phase
     * @return the metric name
     */
    private String buildPhaseMetricName(@NonNull final String metricsNamePrefix, @NonNull final T phase) {
        return metricsNamePrefix + "_fraction_" + phase;
    }

    /**
     * Build the metric name for the average absolute time spent in a given phase.
     *
     * @param metricsNamePrefix the base name of the metric
     * @param phase             the phase
     * @return the metric name
     */
    private String buildAbsoluteTimeMetricName(@NonNull final String metricsNamePrefix, @NonNull final T phase) {
        return metricsNamePrefix + "_time_" + phase;
    }

    /**
     * Register metrics for this object.
     *
     * @param metrics           the metrics object
     * @param metricsCategory   the category for metrics created by this object
     * @param metricsNamePrefix the base name of the metric
     * @param phases            the phases
     */
    private void registerMetrics(
            @NonNull final Metrics metrics,
            @NonNull final String metricsCategory,
            @NonNull final String metricsNamePrefix,
            @NonNull final Set<T> phases) {

        if (fractionMetricsEnabled) {
            for (final T phase : phases) {
                final FractionalTimer timer = fractionalTimers.get(phase);
                timer.registerMetric(
                        metrics,
                        metricsCategory,
                        buildPhaseMetricName(metricsNamePrefix, phase),
                        "Fraction (out of 1.0) of time spent in phase " + phase.name());
            }
        }

        if (absoluteTimeMetricsEnabled) {
            for (final T phase : phases) {
                final RunningAverageMetric.Config config = new RunningAverageMetric.Config(
                                metricsCategory, buildAbsoluteTimeMetricName(metricsNamePrefix, phase))
                        .withDescription("Average time spent in phase " + phase.name())
                        .withUnit(absoluteTimeUnit.getName());

                final RunningAverageMetric metric = metrics.getOrCreate(config);
                absoluteTimeMetrics.put(phase, metric);
            }
        }
    }
}
