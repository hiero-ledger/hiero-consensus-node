// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.statistics.internal;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;

import com.swirlds.base.time.Time;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * Keep a running history of a double value vs. time. History is divided into at most maxbins different bins. It records
 * the min and max value, and the min and max time, for each bin.
 * <p>
 * There are many class variables. Other classes should only read, not write, those variables and the elements of their
 * arrays. This is not thread safe. There must never be one thread reading these variables while another thread is
 * calling any of the methods.
 */
public class StatsBuffer {

    /**
     * if the value is less than max, return value + 1; else return this value
     */
    private static final IntBinaryOperator INC_BY_ONE_WITHIN_MAX = (value, max) -> {
        if (value < max) {
            return value + 1;
        } else {
            return value;
        }
    };

    /**
     * return half of an int value
     */
    private static final IntUnaryOperator DIVIDE_NUM_BY_TWO = value -> value / 2;

    private final Time time;

    /** the time record() was first called, in seconds */
    private double start = -1;

    /**
     * record() will ignore all inputs for startDelay seconds, starting from the first time it's called
     */
    private final double startDelay;

    /**
     * 0 if storing all of history. Else store only recent history where each bin covers binSeconds seconds.
     */
    private final double binSeconds;

    /**
     * store at most this many bins in the history
     */
    private final int maxBins;

    /**
     * min of the all x values in each bin
     */
    private final double[] xMins;

    /**
     * max of the all x values in each bin
     */
    private final double[] xMaxs;

    /**
     * min of the all y values in each bin
     */
    private final double[] yMins;

    /**
     * max of the all y values in each bin
     */
    private final double[] yMaxs;

    /**
     * average of all the y values in each bin
     */
    private final double[] yAvgs;

    /**
     * variance of all the y values in each bin (squared standard deviation, NOT Bessel corrected)
     */
    private final double[] yVars;

    /**
     * number of bins currently stored in all the arrays
     */
    private final AtomicInteger numBins;

    /**
     * index in all arrays of the bin with the oldest data
     */
    private int firstBin = 0;

    /**
     * index in all arrays of the bin currently being added to (-1 if no bins exist)
     */
    private int currBin = -1;

    /**
     * if binSeconds==0, then this is the number of samples in each bin other than the last
     */
    private long numPerBin = 1;

    /**
     * if binSeconds==0, then this is number of samples in the last bin
     */
    private long numLastBin = 0;

    /**
     * Store a history of samples combined into at most maxBins bins. If recentSeconds is zero, then all of history is
     * stored, with an equal number of samples in each bin. Otherwise, only the last recentSeconds seconds of history is
     * stored, with maxBins different bins each covering an equal fraction of that period.
     * <p>
     * If recentSeconds &gt; 0, then empty bins are not stored, so some of the older bins (more than recentSeconds old)
     * can continue to exist until enough newer bins are collected to discard them.
     * <p>
     * The maxBins must be even. If it's odd, it will be incremented, so passing in 99 is the same as passing in 100.
     *
     * @param maxBins       the maximum number of bins to store (must be even)
     * @param recentSeconds the max period of time covered by all the stored data, in seconds (or 0 if covering all of
     *                      history)
     * @param startDelay    record() will ignore all inputs for the first startDelay seconds, starting from the first
     *                      time it's called
     */
    public StatsBuffer(final int maxBins, final double recentSeconds, final double startDelay) {
        this(maxBins, recentSeconds, startDelay, Time.getCurrent());
    }

    public StatsBuffer(final int maxBins, final double recentSeconds, final double startDelay, final Time time) {
        this.time = time;
        this.maxBins = maxBins + (maxBins % 2); // add 1 if necessary to make it even
        this.binSeconds = recentSeconds / maxBins;
        this.startDelay = startDelay;
        xMins = new double[this.maxBins];
        xMaxs = new double[this.maxBins];
        yMins = new double[this.maxBins];
        yMaxs = new double[this.maxBins];
        yAvgs = new double[this.maxBins];
        yVars = new double[this.maxBins];
        numBins = new AtomicInteger();
    }

    /**
     * get the number of bins currently in use
     *
     * @return the number of bins currently in use
     */
    public int numBins() {
        return numBins.get();
    }

    /**
     * return the average of all x values in bin i, where i=0 is the oldest and i=numBins-1 is the newest.
     *
     * @param i the index for the bin
     * @return the average
     */
    public double xAvg(final int i) {
        final int ii = (firstBin + i) % numBins();
        return (xMins[ii] + xMaxs[ii]) / 2;
    }

    /**
     * return the average of all y values in bin i, where i=0 is the oldest and i=numBins-1 is the newest.
     *
     * @param i the index for the bin
     * @return the average
     */
    public double yAvg(final int i) {
        final int ii = (firstBin + i) % numBins();
        return yAvgs[ii];
    }

    /**
     * return the min of all x values in bin i, where i=0 is the oldest and i=numBins-1 is the newest.
     *
     * @param i the index for the bin
     * @return the average
     */
    public double xMin(final int i) {
        final int ii = (firstBin + i) % numBins();
        return xMins[ii];
    }

    /**
     * return the min of all y values in bin i, where i=0 is the oldest and i=numBins-1 is the newest.
     *
     * @param i the index for the bin
     * @return the average
     */
    public double yMin(final int i) {
        final int ii = (firstBin + i) % numBins();
        return yMins[ii];
    }

    /**
     * return the max of all x values in bin i, where i=0 is the oldest and i=numBins-1 is the newest.
     *
     * @param i the index for the bin
     * @return the average
     */
    public double xMax(final int i) {
        final int ii = (firstBin + i) % numBins();
        return xMaxs[ii];
    }

    /**
     * return the max of all y values in bin i, where i=0 is the oldest and i=numBins-1 is the newest.
     *
     * @param i the index for the bin
     * @return the average
     */
    public double yMax(final int i) {
        final int ii = (firstBin + i) % numBins();
        return yMaxs[ii];
    }

    /**
     * return the standard deviation of all y values in bin i, where i=0 is the oldest and i=numBins-1 is the newest.
     *
     * @param i the index for the bin
     * @return the average
     */
    public double yStd(final int i) {
        final int ii = (firstBin + i) % numBins();
        return Math.sqrt(yVars[ii]);
    }

    /**
     * return the standard deviation of most recent values
     *
     * @return the standard deviation
     */
    public double yStdMostRecent() {
        final int currNumBins = numBins();
        if (currNumBins > 0) {
            final int ii = (currBin) % currNumBins;
            return Math.sqrt(yVars[ii]);
        } else {
            return 0;
        }
    }

    /**
     * find the minimum of all stored y values of from the most recent bin
     *
     * @return the minimum y value
     */
    public double yMinMostRecent() {
        final int currNumBins = numBins();
        if (currNumBins > 0) {
            final int ii = (currBin) % currNumBins;
            return yMins[ii];
        } else {
            return 0;
        }
    }

    /**
     * find the maximum of all stored y values from the most recent bin
     *
     * @return the maximum y value
     */
    public double yMaxMostRecent() {
        final int currNumBins = numBins();
        if (currNumBins > 0) {
            final int ii = (currBin) % currNumBins;
            return yMaxs[ii];
        } else {
            return 0;
        }
    }

    /**
     * find the minimum of all stored x values
     *
     * @return the minimum x value
     */
    public double xMin() {
        double v = Double.MAX_VALUE;
        for (int i = 0; i < numBins(); i++) {
            v = Math.min(v, xMins[(firstBin + i) % maxBins]);
        }
        return v;
    }

    /**
     * find the maximum of all stored x values
     *
     * @return the maximum x value
     */
    public double xMax() {
        double v = -Double.MAX_VALUE;
        for (int i = 0; i < numBins(); i++) {
            v = Math.max(v, xMaxs[(firstBin + i) % maxBins]);
        }
        return v;
    }

    /**
     * find the minimum of all stored y values
     *
     * @return the minimum y value
     */
    public double yMin() {
        double v = Double.MAX_VALUE;
        for (int i = 0; i < numBins(); i++) {
            v = Math.min(v, yMins[(firstBin + i) % maxBins]);
        }
        return v;
    }

    /**
     * find the maximum of all stored y values
     *
     * @return the maximum y value
     */
    public double yMax() {
        double v = -Double.MAX_VALUE;
        for (int i = 0; i < numBins(); i++) {
            v = Math.max(v, yMaxs[(firstBin + i) % maxBins]);
        }
        return v;
    }

    /**
     * Return the time in seconds right now
     *
     * @return the time in seconds right now
     */
    public double xNow() {
        return time.nanoTime() * NANOSECONDS_TO_SECONDS;
    }

    /**
     * Merge the given (age,value) into the latest existing bin.
     *
     * @param x the x value to store (time in seconds)
     * @param y the y value to store
     */
    private void addToBin(final double x, final double y) {
        numLastBin++;
        final long n = numLastBin;
        xMins[currBin] = Math.min(xMins[currBin], x);
        xMaxs[currBin] = Math.max(xMaxs[currBin], x);
        yMins[currBin] = Math.min(yMins[currBin], y);
        yMaxs[currBin] = Math.max(yMaxs[currBin], y);
        yAvgs[currBin] = yAvgs[currBin] * (n - 1) / n + y / n;
        final double d = y - yAvgs[currBin];
        yVars[currBin] = yVars[currBin] * (n - 1) / n + d * d / (n - 1);
    }

    /**
     * Create a new bin at the given index in all the arrays, holding only the given (x, y) sample.
     *
     * @param x the x value to store (time in seconds)
     * @param y the y value to store
     */
    public void createBin(final double x, final double y) {
        // index in arrays for the new bin, right after the last bin, with wrapping
        final int i = (currBin + 1) % maxBins;
        xMins[i] = x;
        xMaxs[i] = x;
        yMins[i] = y;
        yMaxs[i] = y;
        yAvgs[i] = y;
        yVars[i] = 0;
        numLastBin = 1;
        currBin = i;

        if (numBins() < maxBins) { // if not full yet, then increment count
            numBins.getAndAccumulate(maxBins, INC_BY_ONE_WITHIN_MAX);
        } else if (binSeconds > 0) { // if full and wrapping around
            firstBin = (firstBin + 1) % maxBins; // then the oldest must have been overwritten
        }
    }

    /**
     * record the given y value, associated with an x value equal to the time right now
     *
     * @param y the value to be recorded
     */
    public void recordValue(final double y) {
        final double x = xNow();
        if (start == -1) { // remember when record() is called for the first time.
            start = x;
        }
        if (x - start < startDelay) { // don't actually record anything during the first half life.
            return;
        }
        if (numBins() == 0) {
            // this is the first sample in all of history
            createBin(x, y);
        } else if (binSeconds > 0 && x < xMins[currBin] + binSeconds) {
            // Storing recent. Should stay in the current bin
            addToBin(x, y);
        } else if (binSeconds > 0) {
            // Storing recent. Should create a new bin, discarding the oldest, if necessary
            createBin(x, y);
        } else if (numLastBin < numPerBin) {
            // Storing all. The latest bin still has room for more samples
            addToBin(x, y);
        } else if (numBins() + 1 < maxBins) {
            // Storing all. Need to create a new bin, and it won't fill the buffer
            createBin(x, y);
        } else {
            // Storing all. Need to create a new bin, which fills the buffer
            createBin(x, y);
            final int currNumBins = numBins();
            // we're now full, so merge pairs of bins to shrink to half the size
            for (int i = 0; i < currNumBins / 2 && (2 * i + 1) < maxBins; i++) {
                // set bin i to the merger of bin j=2*i with bin k=2*i+1
                // and make sure the indices are not out of bounds
                final int j = 2 * i;
                final int k = 2 * i + 1;
                xMins[i] = Math.min(xMins[j], xMins[k]);
                xMaxs[i] = Math.max(xMaxs[j], xMaxs[k]);
                yMins[i] = Math.min(yMins[j], yMins[k]);
                yMaxs[i] = Math.max(yMaxs[j], yMaxs[k]);
                yAvgs[i] = (yAvgs[j] + yAvgs[k]) / 2;
                final double dj = yAvgs[j] - yAvgs[i];
                final double dk = yAvgs[k] - yAvgs[i];
                yVars[i] = (yVars[j] + yVars[k] + dj * dj + dk * dk) / 2;
            }
            final int newNumBins = numBins.updateAndGet(DIVIDE_NUM_BY_TWO);
            currBin = Math.max(0, newNumBins - 1);
            numPerBin *= 2;
            numLastBin = numPerBin;
        }
    }
}
