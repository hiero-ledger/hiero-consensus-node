// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.statistics;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.statistics.internal.StatsBuffer;
import java.util.Objects;

/**
 * This class measures how many times per second the cycle() method is called. It is recalculated every
 * period, where its period is 0.1 seconds by default. If instantiated with gamma=0.9, then half the
 * weighting comes from the last 7 periods. If 0.99 it's 70 periods, 0.999 is 700, etc.
 * <p>
 * The timer starts at instantiation, and can be reset with the reset() method.
 *
 * @deprecated Use {@link com.swirlds.common.metrics.SpeedometerMetric} instead
 */
@Deprecated(forRemoval = true)
public class StatsSpeedometer implements StatsBuffered {

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
    private double cyclesPerSecond = 0;

    /**
     * half the weight = this many sec
     */
    private double halfLife = 7;

    /**
     * the entire history of values of this speedometer
     */
    private StatsBuffer allHistory = null;

    /**
     * the recent history of values of this speedometer
     */
    private StatsBuffer recentHistory = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public StatsBuffer getAllHistory() {
        return allHistory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StatsBuffer getRecentHistory() {
        return recentHistory;
    }

    /**
     * Instantiate a Speedometer with the given halfLife and start the measurements right now. This will
     * calculate exponentially weighted averages of the number of times update() is called per second. Where
     * the exponential weighting has a half life of halfLife seconds. This will record the history, so it is
     * the same as using the constructor new StatsSpeedometer(halfLife, true).
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     */
    public StatsSpeedometer(final double halfLife) {
        this(halfLife, true);
    }

    /**
     * Instantiate a Speedometer with the given halfLife and start the measurements right now. This will
     * calculate exponentially weighted averages of the number of times update() is called per second. Where
     * the exponential weighting has a half life of halfLife seconds.
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     * @param saveHistory
     * 		true if a StatsBuffer of recent and all history should be created and used
     */
    @SuppressWarnings("removal")
    public StatsSpeedometer(final double halfLife, final boolean saveHistory) {
        this(halfLife, saveHistory, Time.getCurrent());
    }

    /**
     * This constructor behaves exactly as the regular one, but permits to inject a {@link Time}.
     * It should only be used internally.
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     * @param time
     * 		the {@code Clock} implementation, typically a mock when testing
     * @deprecated this constructor should only be used internally and will become non-public at some point
     */
    @Deprecated(forRemoval = true)
    public StatsSpeedometer(final double halfLife, final Time time) {
        this(halfLife, true, time);
    }

    /**
     * This constructor behaves exactly as the regular one, but permits to inject a {@link Time}.
     * It should only be used internally.
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     * @param time
     * 		the {@code Clock} implementation, typically a mock when testing
     * @throws NullPointerException in case {@code time} parameter is {@code null}
     * @deprecated this constructor should only be used internally and will become non-public at some point
     */
    @Deprecated(forRemoval = true)
    public StatsSpeedometer(final double halfLife, final boolean saveHistory, final Time time) {
        this.time = Objects.requireNonNull(time, "time must not be null");
        final long now = time.nanoTime();
        this.startTime = now;
        this.lastTime = now;
        reset(halfLife, saveHistory);
    }

    /**
     * Start over on the measurements and counts, to get an exponentially-weighted average number of calls
     * to cycle() per second, with the weighting having a half life of halfLife seconds. This is equivalent
     * to instantiating a new Speedometer. This will also record a history of values, so calling this is the
     * same as calling reset(halfLife, true).
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     */
    @Override
    public void reset(final double halfLife) {
        reset(halfLife, true);
    }

    /**
     * Start over on the measurements and counts, to get an exponentially-weighted average number of calls
     * to cycle() per second, with the weighting having a half life of halfLife seconds. This is equivalent
     * to instantiating a new Speedometer. If halfLife &lt; 0.01 then 0.01 will be used.
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     * @param saveHistory
     * 		true if a StatsBuffer of recent and all history should be created and used
     */
    private void reset(final double halfLife, final boolean saveHistory) {

        final StatSettings settings = StatSettingsFactory.get();

        this.halfLife = Math.max(0.01, halfLife); // clip to 0.01 to avoid division by zero problems
        startTime = time.nanoTime(); // find average since this time
        lastTime = startTime; // the last time update() was called
        cyclesPerSecond = 0; // estimated average calls to cycle() per second
        if (saveHistory) {
            allHistory = new StatsBuffer(settings.getBufferSize(), 0, settings.getSkipSeconds(), time);
            recentHistory = new StatsBuffer(settings.getBufferSize(), settings.getRecentSeconds(), 0, time);
        } else {
            allHistory = null;
            recentHistory = null;
        }
    }

    /**
     * Get the average number of times per second the cycle() method was called. This is an
     * exponentially-weighted average of recent timings.
     *
     * @return the estimated number of calls to cycle() per second, recently
     */
    public double getCyclesPerSecond() {
        // return a value discounted to right now, but don't save it as a data point
        return update(0, false);
    }

    /**
     * This is the method to call repeatedly. The average calls per second will be calculated.
     *
     * @return the estimated number of calls to cycle() per second, recently
     */
    public double cycle() {
        return update(1);
    }

    /**
     * calling update(N) is equivalent to calling cycle() N times almost simultaneously. Calling cycle() is
     * equivalent to calling update(1). Calling update(0) will update the estimate of the cycles per second
     * by looking at the current time (to update the seconds) without incrementing the count of the cycles.
     * So calling update(0) repeatedly with no calls to cycle() will cause the cycles per second estimate to
     * go asymptotic to zero.
     * <p>
     * The speedometer initially keeps a simple, uniformly-weighted average of the number of calls to
     * cycle() per second since the start of the run. Over time, that makes each new call to cycle() have
     * less weight (because there are more of them). Eventually, the weight of a new call drops below the
     * weight it would have under the exponentially-weighted average. At that point, it switches to the
     * exponentially-weighted average.
     *
     * @param numCycles
     * 		number of cycles to record
     * @return estimated number of calls to cycle() per second
     */
    public synchronized double update(final double numCycles) {
        return update(numCycles, true);
    }

    /**
     * The same as update(numCycles), except this will only record a new data point if recordData==true
     *
     * @param numCycles
     * 		number of cycles to record
     * @return estimated number of calls to cycle() per second
     */
    private synchronized double update(final double numCycles, final boolean recordData) {
        final long currentTime = time.nanoTime();
        final double t1 = (lastTime - startTime) / 1.0e9; // seconds: start to last update
        final double t2 = (currentTime - startTime) / 1.0e9; // seconds: start to now
        final double dt = (currentTime - lastTime) / 1.0e9; // seconds: last update to now
        if (t2 >= 1e-9) { // skip cases were no time has passed since last call
            if (1.0 / t2 > LN_2 / halfLife) { // during startup period, so do uniformly-weighted average
                cyclesPerSecond = (cyclesPerSecond * t1 + numCycles) / t2;
            } else { // after startup, so do exponentially-weighted average with given half life
                cyclesPerSecond = cyclesPerSecond * Math.pow(0.5, dt / halfLife) + numCycles * LN_2 / halfLife;
            }
        }
        lastTime = currentTime;
        if (allHistory != null && recordData) {
            allHistory.recordValue(cyclesPerSecond);
            recentHistory.recordValue(cyclesPerSecond);
        }
        return cyclesPerSecond;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMean() {
        return cyclesPerSecond;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMax() { // if allHistory is empty return recentHistory value
        if (allHistory.numBins() > 0) {
            return allHistory.yMaxMostRecent();
        } else if (recentHistory.numBins() > 0) {
            return recentHistory.yMaxMostRecent();
        } else {
            return 0; // return 0 when bins are empty to avoid put MAX_VALUE in output
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMin() { // if allHistory is empty return recentHistory value
        if (allHistory.numBins() > 0) {
            return allHistory.yMinMostRecent();
        } else if (recentHistory.numBins() > 0) {
            return recentHistory.yMinMostRecent();
        } else {
            return 0; // return 0 when bins are empty to avoid put MAX_VALUE in output
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getStdDev() { // if allHistory is empty return recentHistory value
        if (allHistory.numBins() > 0) {
            return allHistory.yStdMostRecent();
        } else if (recentHistory.numBins() > 0) {
            return recentHistory.yStdMostRecent();
        } else {
            return 0; // return 0 when bins are empty to avoid put MAX_VALUE in output
        }
    }
}

// derivation of the formulas in update():
// if only update(n) is called (not cycle), and if it is always called
// exactly once a second since the start, then exponential weighting would be:
// cyclesPerSecond = gamma * cyclesPerSecond + (1-gamma) * n
// It is easy to verify that if we want a half life of halfLife seconds, we use:
// gamma = Math.pow(0.5, 1 / halfLife)
// If we have skipped calling update() for the last dt seconds,
// each of which should have been calls to update(0), but
// those calls weren't made, then calling update(n) now should do:
// cyclesPerSecond = Math.pow(gamma, dt) * cyclesPerSecond + (1-gamma) * n
// Suppose the calls to update(n) aren't once a second, but are K times a second.
// Then we should multiply all 3 variables (dt, halfLife, n) by K. Plugging
// gamma into the first equation, and taking the limit as K goes to infinity gives:
// cyclesPerSecond = Math.pow(0.5, dt / halfLife) * cyclesPerSecond + ln2 / halfLife * n
// which is the formula used in the function above.
// The other formula (during startup) is simpler. If cyclesPerSecond is the average
// cycle calls per second during the first t1 seconds, then multiplying it by t1 gives the count
// of cycle calls up to then. Adding numCycles gives the count up to now. Dividing by t2 gives
// the average per second up to now. So the first formula is the un-weighted average. The
// if statement switches between them as soon as the weight on numCycles for un-weighted
// drops below the weight for weighted.
