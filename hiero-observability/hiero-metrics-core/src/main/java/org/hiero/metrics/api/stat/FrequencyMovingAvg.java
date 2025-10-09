// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import com.swirlds.base.time.Time;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.utils.Unit;

// Similar to com.swirlds.common.metrics.statistics.StatsSpeedometer
public class FrequencyMovingAvg implements DoubleSupplier {

    private static final double LN_2 = Math.log(2);

    private final Time time;

    /**
     * find average since this time
     */
    private long startTime;

    /**
     * the last time update() was called
     */
    private long lastTime;

    /**
     * estimated average calls/sec to cycle()
     */
    private volatile double frequency = 0;

    /**
     * half the weight = this many sec
     */
    private final double halfLife;

    public FrequencyMovingAvg(final double halfLife, Time time) {
        this.time = time;
        this.halfLife = Math.max(0.01, halfLife);

        final long now = time.nanoTime();
        this.startTime = now;
        this.lastTime = now;
        reset();
    }

    public static MetricKey<GaugeAdapter<DoubleSupplier, FrequencyMovingAvg>> key(String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    public static GaugeAdapter.Builder<DoubleSupplier, FrequencyMovingAvg> metricBuilder(
            double halfLife, Time time, MetricKey<GaugeAdapter<DoubleSupplier, FrequencyMovingAvg>> key) {
        return GaugeAdapter.builder(
                        key,
                        StatUtils.asInitializer(halfLife),
                        init -> new FrequencyMovingAvg(init.getAsDouble(), time),
                        FrequencyMovingAvg::getAsDouble)
                .withReset(FrequencyMovingAvg::reset)
                .withUnit(Unit.FREQUENCY_UNIT);
    }

    public static GaugeAdapter.Builder<DoubleSupplier, FrequencyMovingAvg> metricBuilder(
            double halfLife, MetricKey<GaugeAdapter<DoubleSupplier, FrequencyMovingAvg>> key) {
        return metricBuilder(halfLife, Time.getCurrent(), key);
    }

    public double update() {
        return update(StatUtils.ONE);
    }

    /**
     * Increase the count by the value provided
     *
     * @param count the amount to increase the count by
     */
    public synchronized double update(final double count) {
        final long currentTime = time.nanoTime();
        final double t1 = (lastTime - startTime) / 1.0e9; // seconds: start to last update
        final double t2 = (currentTime - startTime) / 1.0e9; // seconds: start to now
        final double dt = (currentTime - lastTime) / 1.0e9; // seconds: last update to now
        if (t2 >= 1e-9) { // skip cases were no time has passed since last call
            if (1.0 / t2 > LN_2 / halfLife) { // during startup period, so do uniformly-weighted average
                frequency = (frequency * t1 + count) / t2;
            } else { // after startup, so do exponentially-weighted average with given half life
                frequency = frequency * Math.pow(0.5, dt / halfLife) + count * LN_2 / halfLife;
            }
        }
        lastTime = currentTime;
        return frequency;
    }

    @Override
    public double getAsDouble() {
        // update rate with zero to make it running when no calls have been made
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
        startTime = time.nanoTime(); // find average since this time
        lastTime = startTime; // the last time update() was called
        frequency = 0; // estimated average calls to cycle() per second
    }
}
