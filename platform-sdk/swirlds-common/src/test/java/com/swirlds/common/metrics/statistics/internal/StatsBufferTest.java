// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.statistics.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class StatsBufferTest {
    private static final int MAX_BINS = 100;
    private static final int START_DELAY = 60;

    @Test
    void recordInFirstHalfLifeTest() {
        StatsBuffer statsBuffer = new StatsBuffer(MAX_BINS, 0, START_DELAY);
        assertEquals(0, statsBuffer.numBins(), "numBins should be 0 after initialization");

        statsBuffer.recordValue(ThreadLocalRandom.current().nextDouble());
        assertEquals(0, statsBuffer.numBins(), "numBins should be 0 during the first half life");
    }

    @Test
    void recordOneValueTest() {
        StatsBuffer statsBuffer = new StatsBuffer(MAX_BINS, 0, 0);
        final double value = ThreadLocalRandom.current().nextDouble();
        statsBuffer.recordValue(value);

        assertEquals(
                1,
                statsBuffer.numBins(),
                "numBins should be 1 after recording a value when current time is not in the first half life");

        assertEquals(value, statsBuffer.yMax(), "when only one value is recorded, yMax should be equal to that value");
        assertEquals(
                statsBuffer.yMax(),
                statsBuffer.yMax(0),
                "when only one value is recorded, yMax() should be equal to yMax(0)");

        assertEquals(value, statsBuffer.yMin(), "when only one value is recorded, yMin should be equal to that value");
        assertEquals(
                statsBuffer.yMin(),
                statsBuffer.yMin(0),
                "when only one value is recorded, yMin() should be equal to yMin(0)");

        assertEquals(0, statsBuffer.yStd(0), "when only one value is recorded, yStd(0) should be 0");
        assertEquals(
                statsBuffer.yStd(0),
                statsBuffer.yStdMostRecent(),
                "when only one value is recorded, yStd(0) should be equal to yStdMostRecent()");

        assertEquals(
                statsBuffer.xMax(),
                statsBuffer.xMin(),
                "when only one value is recorded, xMax() should be equal to xMin()");
        assertEquals(
                statsBuffer.xMax(),
                statsBuffer.xMin(),
                "when only one value is recorded, xMax() should be equal to xMin()");
    }

    @Test
    void recordMultipleValueTest() {
        StatsBuffer statsBuffer = new StatsBuffer(MAX_BINS, 0, 0);
        final int minValue = 1;
        final int maxValue = MAX_BINS;
        for (int i = minValue; i < maxValue; i++) {
            statsBuffer.recordValue(i);
        }
        assertEquals(
                MAX_BINS - 1,
                statsBuffer.numBins(),
                "numBins should be MAX_BINS - 1 after recording MAX_BINS - 1 values");

        // when numBins is MAX_BINS - 1, recording one more value will make numBins be MAX_BINS / 2
        statsBuffer.recordValue(maxValue);
        assertEquals(
                MAX_BINS / 2,
                statsBuffer.numBins(),
                "when numBins is MAX_BINS - 1, record one more value will make numBins be MAX_BINS / 2");

        assertEquals(maxValue, statsBuffer.yMax(), "yMax should be the maximum value recorded so far");

        assertEquals(minValue, statsBuffer.yMin(), "yMin should be the minimum value recorded so far");
    }
}
