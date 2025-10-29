// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import com.swirlds.base.time.Time;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.ToLongOrDoubleFunction;

// similar to com.swirlds.common.metrics.statistics.StatsRunningAverage
public final class MovingAverageStat implements DoubleSupplier {
    /**
     * each recordValue(X) counts as X calls to values.cycle()
     */
    private final FrequencyMovingAvg values;

    /**
     * each recordValue(X) counts as 1 call to times.cycle()
     */
    private final FrequencyMovingAvg times;

    /**
     * the estimated running average
     */
    private volatile double mean = 0;

    /**
     * Did we just perform a reset, and are about to record the first value?
     */
    private boolean firstRecord;

    public MovingAverageStat(final double halfLife, Time time) {
        firstRecord = true;
        values = new FrequencyMovingAvg(halfLife, time);
        times = new FrequencyMovingAvg(halfLife, time);
        reset();
    }

    public static MetricKey<GaugeAdapter<DoubleSupplier, MovingAverageStat>> key(String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    public static GaugeAdapter.Builder<DoubleSupplier, MovingAverageStat> metricBuilder(
            double halfLife, Time time, MetricKey<GaugeAdapter<DoubleSupplier, MovingAverageStat>> key) {
        return GaugeAdapter.builder(
                        key,
                        StatUtils.asInitializer(halfLife),
                        init -> new MovingAverageStat(init.getAsDouble(), time),
                        new ToLongOrDoubleFunction<>(MovingAverageStat::getAsDouble))
                .withReset(MovingAverageStat::reset);
    }

    public static GaugeAdapter.Builder<DoubleSupplier, MovingAverageStat> metricBuilder(
            double halfLife, MetricKey<GaugeAdapter<DoubleSupplier, MovingAverageStat>> key) {
        return metricBuilder(halfLife, Time.getCurrent(), key);
    }

    public double update() {
        return update(StatUtils.ONE);
    }

    public synchronized double update(double value) {
        if (Double.isNaN(value)) {
            return StatUtils.ZERO;
        }

        values.update(value);
        times.update(StatUtils.ONE);

        if (firstRecord || value == mean) {
            // if the same value is always given since the beginning, then avoid roundoff errors
            firstRecord = false;
            mean = value;
        } else {
            mean = values.getAsDouble() / times.getAsDouble();
        }
        return mean;
    }

    @Override
    public double getAsDouble() {
        return update(StatUtils.ZERO);
    }

    public double getAndReset() {
        final double result = getAsDouble();
        reset();
        return result;
    }

    /**
     * Start over on the measurements and counts, to get an exponentially-weighted average number of calls
     * to cycle() per second, with the weighting having a half life of halfLife seconds. This is equivalent
     * to instantiating a new instance of the class. If halfLife &lt; 0.01 then 0.01 will be used.
     */
    public synchronized void reset() {
        firstRecord = true;
        values.reset();
        times.reset();
    }
}
