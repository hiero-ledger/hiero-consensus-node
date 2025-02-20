// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.statistics;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.statistics.internal.StatsBuffer;
import com.swirlds.logging.legacy.LogMarker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class maintains a running average of some numeric value. It is exponentially weighted in time, with
 * a given half life. If it is always given the same value, then that value will be the average, regardless
 * of the timing.
 *
 * @deprecated Use {@link com.swirlds.common.metrics.RunningAverageMetric} instead
 */
@Deprecated(forRemoval = true)
public class StatsRunningAverage implements StatsBuffered {

    private static final Logger logger = LogManager.getLogger(StatsRunningAverage.class);

    private final Time time;

    /**
     * the estimated running average
     */
    private double mean = 0;

    /**
     * each recordValue(X) counts as X calls to values.cycle()
     */
    @SuppressWarnings("removal")
    private StatsSpeedometer values;

    /**
     * each recordValue(X) counts as 1 call to times.cycle()
     */
    @SuppressWarnings("removal")
    private StatsSpeedometer times;

    /**
     * Did we just perform a reset, and are about to record the first value?
     */
    private boolean firstRecord = true;

    // FORMULA: mean = values.cyclesPerSeconds() / times.cyclesPerSecond()

    /**
     * the entire history of means of this RunningAverage
     */
    private StatsBuffer allHistory;

    /**
     * the recent history of means of this RunningAverage
     */
    private StatsBuffer recentHistory;

    /**
     * get the entire history of values of means of this RunningAverage. The caller should not modify it.
     */
    @Override
    public StatsBuffer getAllHistory() {
        return allHistory;
    }

    /**
     * get the recent history of values of means of this RunningAverage. The caller should not modify it.
     */
    @Override
    public StatsBuffer getRecentHistory() {
        return recentHistory;
    }

    /**
     * instantiation a RunningAverage and start the measurements right now.
     */
    public StatsRunningAverage() {
        this(10); // default: half the weight is in the last 10 seconds, for weighted average
    }

    /**
     * Instantiate a RunningAverage with the given halfLife and start the measurements right now. This will
     * calculate exponentially weighted averages of the values passed to recordValue(), where the
     * exponential weighting has a half life of halfLife seconds.
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     */
    @SuppressWarnings("removal")
    public StatsRunningAverage(final double halfLife) {
        this(halfLife, Time.getCurrent());
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
    public StatsRunningAverage(final double halfLife, final Time time) {
        this.time = time;
        reset(halfLife);
    }

    /**
     * Start over on the measurements and counts, to get an exponentially-weighted average of the values
     * passed to recordValue(), with the weighting having a half life of halfLife seconds. This is
     * equivalent to instantiating a new RunningAverage.
     *
     * @param halfLife
     * 		half of the exponential weighting comes from the last halfLife seconds
     */
    @SuppressWarnings("removal")
    @Override
    public void reset(final double halfLife) {

        final StatSettings settings = StatSettingsFactory.get();

        firstRecord = true;
        values = new StatsSpeedometer(halfLife, false, time);
        times = new StatsSpeedometer(halfLife, false, time);
        allHistory = new StatsBuffer(settings.getBufferSize(), 0, settings.getSkipSeconds());
        recentHistory = new StatsBuffer(settings.getBufferSize(), settings.getRecentSeconds(), 0);
    }

    /**
     * Incorporate "value" into the running average. If it is the same on every call, then the average will
     * equal it, no matter how those calls are timed. If it has various values on various calls, then the
     * running average will weight the more recent ones more heavily, with a half life of halfLife seconds,
     * where halfLife was passed in when this object was instantiated.
     * <p>
     * If this is called repeatedly with a value of X over a long period, then suddenly all calls start
     * having a value of Y, then after halflife seconds, the average will have moved halfway from X to Y,
     * regardless of how often update was called, as long as it is called at least once at the end of that
     * period.
     *
     * @param value
     * 		the value to incorporate into the running average
     */
    public void recordValue(final double value) {
        if (Double.isNaN(value)) { // java getSystemCpuLoad returns NaN at beginning
            return;
        }
        // StatsRunningAverage is not thread safe, despite this, it is accessed by many threads throughout the platform
        // Until we do a full statistics refactor, this try catch is a safeguard against any issues that might occur
        // from this issue
        try {
            if (firstRecord || value == mean) {
                // if the same value is always given since the beginning, then avoid roundoff errors
                firstRecord = false;
                values.update(value);
                times.update(1);
                mean = value;
            } else {
                mean = values.update(value) / times.update(1);
            }
            allHistory.recordValue(mean);
            recentHistory.recordValue(mean);
        } catch (Exception e) {
            logger.error(LogMarker.EXCEPTION.getMarker(), "Exception while updating statistics!", e);
        }
    }

    /**
     * Get the average of recent calls to recordValue(). This is an exponentially-weighted average of recent
     * calls, with the weighting by time, not by number of calls to recordValue().
     *
     * @return the running average as of the last time recordValue was called
     */
    public double getWeightedMean() {
        return mean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMean() {
        return mean;
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
