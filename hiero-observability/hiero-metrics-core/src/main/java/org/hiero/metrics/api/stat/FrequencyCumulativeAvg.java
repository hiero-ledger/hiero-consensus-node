// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import static org.hiero.metrics.api.stat.StatUtils.INT_NO_OP;
import static org.hiero.metrics.api.stat.StatUtils.INT_SUM;

import com.swirlds.base.time.Time;
import com.swirlds.base.units.UnitConstants;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleBiFunction;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.api.stat.container.AtomicIntPair;
import org.hiero.metrics.api.utils.Unit;

/**
 * A statistic that atomically computes the frequency (counts per second) of events
 * using {@link AtomicIntPair} as the underlying container.
 * <p>
 * The granularity of this metric is a millisecond.
 * This metric needs to be reset once every 25 days in order to remain accurate due to integer limit.
 */
public final class FrequencyCumulativeAvg implements DoubleSupplier {

    private final AtomicIntPair container = new AtomicIntPair(INT_NO_OP, INT_SUM);
    private final Time time;
    private final ToDoubleBiFunction<Integer, Integer> compute;

    /**
     * A default constructor that uses the OS time provider
     */
    public FrequencyCumulativeAvg() {
        this(Time.getCurrent());
    }

    /**
     * A constructor where a custom {@link Time} instance could be supplied
     *
     * @param time time provider
     */
    public FrequencyCumulativeAvg(@NonNull Time time) {
        this.time = Objects.requireNonNull(time, "time provider cannot be null");

        compute = (startTime, count) -> {
            int millisElapsed = elapsed(startTime, currentTimeMillis());

            if (millisElapsed == 0) {
                // theoretically this is infinity, but we will say that 1 millisecond of time passed because some time
                // has to have passed
                millisElapsed = 1;
            }
            return count / (millisElapsed * UnitConstants.MILLISECONDS_TO_SECONDS);
        };

        reset();
    }

    /**
     * Creates a {@link MetricKey} for a {@link GaugeAdapter} that holds a {@link FrequencyCumulativeAvg}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    public static MetricKey<GaugeAdapter<FrequencyCumulativeAvg>> key(String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    /**
     * Creates a {@link GaugeAdapter.Builder} for a {@link FrequencyCumulativeAvg}.
     * Metric will reset the cumulative average after each export.
     *
     * @param time time provider
     * @param key  the metric key
     * @return the metric builder
     */
    public static GaugeAdapter.Builder<FrequencyCumulativeAvg> metricBuilder(
            Time time, MetricKey<GaugeAdapter<FrequencyCumulativeAvg>> key) {
        return GaugeAdapter.builder(
                        key,
                        () -> new FrequencyCumulativeAvg(time),
                        new ToNumberFunction<>(FrequencyCumulativeAvg::getAndReset))
                .withReset(FrequencyCumulativeAvg::reset)
                .withUnit(Unit.FREQUENCY_UNIT);
    }

    /**
     * Creates a {@link GaugeAdapter.Builder} for a {@link FrequencyCumulativeAvg} with OS time provider.
     * Metric will reset the cumulative average after each export.
     *
     * @param key the metric key
     * @return the metric builder
     */
    public static GaugeAdapter.Builder<FrequencyCumulativeAvg> metricBuilder(
            MetricKey<GaugeAdapter<FrequencyCumulativeAvg>> key) {
        return metricBuilder(Time.getCurrent(), key);
    }

    /**
     * Creates a {@link GaugeAdapter.Builder} for a {@link FrequencyCumulativeAvg} with OS time provider.
     * Metric will reset the cumulative average after each export.
     *
     * @param name the metric name
     * @return the metric builder
     */
    public static GaugeAdapter.Builder<FrequencyCumulativeAvg> metricBuilder(String name) {
        return metricBuilder(Time.getCurrent(), key(name));
    }

    /**
     * Increase the count by 1
     */
    public void count() {
        count(1);
    }

    /**
     * Increase the count by the value provided
     *
     * @param count the amount to increase the count by
     */
    public void count(final int count) {
        container.accumulate(0, count);
    }

    /**
     * Get the current frequency (counts per second) without resetting the statistic.
     *
     * @return the current frequency
     */
    @Override
    public double getAsDouble() {
        return container.computeDouble(compute);
    }

    /**
     * Get the current frequency (counts per second) and reset the statistic.
     *
     * @return the current frequency
     */
    public double getAndReset() {
        return container.computeDoubleAndSet(compute, currentTimeMillis(), 0);
    }

    /**
     * Reset the frequency cumulative average to the initial value.
     */
    public void reset() {
        container.set(currentTimeMillis(), 0);
    }

    private int currentTimeMillis() {
        return (int) (time.currentTimeMillis() % Integer.MAX_VALUE);
    }

    /**
     * @return the elapsed time in from the start time until the end time
     */
    private int elapsed(final int startTime, final int endTime) {
        if (endTime >= startTime) {
            return endTime - startTime;
        } else {
            // if the lower 31 bits of the epoch has rolled over, the start time will be bigger than current time
            return Integer.MAX_VALUE - startTime + endTime;
        }
    }
}
