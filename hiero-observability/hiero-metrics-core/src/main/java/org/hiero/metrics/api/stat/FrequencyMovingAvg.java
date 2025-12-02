// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import com.swirlds.base.time.Time;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.api.utils.Unit;

/**
 * This class measures how many times per second the update() method is called. It is recalculated every
 * period, where its period is 0.1 seconds by default. If instantiated with gamma=0.9, then half the
 * weighting comes from the last 7 periods. If 0.99 it's 70 periods, 0.999 is 700, etc.
 * <p>
 * The timer starts at instantiation, and can be reset with the reset() method.
 * <p>
 * Similar to com.swirlds.common.metrics.statistics.StatsSpeedometer
 */
public final class FrequencyMovingAvg implements DoubleSupplier {

    private static final double LN_2 = Math.log(2);
    private static final double NANOS_PER_SECOND = 1.0e9;

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

    private final double halfLifeFactor;

    public FrequencyMovingAvg(final double halfLife, Time time) {
        this.time = time;
        this.halfLife = Math.max(0.01, halfLife);
        halfLifeFactor = LN_2 / this.halfLife;

        final long now = time.nanoTime();
        this.startTime = now;
        this.lastTime = now;
        reset();
    }

    public static MetricKey<GaugeAdapter<FrequencyMovingAvg>> key(String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    public static GaugeAdapter.Builder<FrequencyMovingAvg> metricBuilder(
            double halfLife, Time time, MetricKey<GaugeAdapter<FrequencyMovingAvg>> key) {
        return GaugeAdapter.builder(
                        key,
                        () -> new FrequencyMovingAvg(halfLife, time),
                        new ToNumberFunction<>(FrequencyMovingAvg::getAsDouble))
                .withReset(FrequencyMovingAvg::reset)
                .withUnit(Unit.FREQUENCY_UNIT);
    }

    public static GaugeAdapter.Builder<FrequencyMovingAvg> metricBuilder(
            double halfLife, MetricKey<GaugeAdapter<FrequencyMovingAvg>> key) {
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
        final double startToLast = (lastTime - startTime) / NANOS_PER_SECOND; // seconds: start to last update
        final double startToNow = (currentTime - startTime) / NANOS_PER_SECOND; // seconds: start to now
        final double lastToNow = (currentTime - lastTime) / NANOS_PER_SECOND; // seconds: last update to now
        if (startToNow >= 1e-9) { // skip cases were no time has passed since last call
            if (1.0 / startToNow > halfLifeFactor) { // during startup period, so do uniformly-weighted average
                frequency = (frequency * startToLast + count) / startToNow;
            } else { // after startup, so do exponentially-weighted average with given half life
                frequency = frequency * Math.pow(0.5, lastToNow / halfLife) + count * halfLifeFactor;
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
