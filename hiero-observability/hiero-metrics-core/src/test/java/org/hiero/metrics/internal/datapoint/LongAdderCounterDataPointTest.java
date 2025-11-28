// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LongAdderCounterDataPointTest {

    @Test
    void testNullInitializerThrows() {
        assertThatThrownBy(() -> new LongAdderCounterDataPoint(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -10L, Long.MIN_VALUE})
    void testNegativeInitializerThrows(long initValue) {
        assertThatThrownBy(() -> new LongAdderCounterDataPoint(() -> initValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Increment value must be non-negative");
    }

    @Test
    void testDataPointWithZeroInitialValue() {
        LongAdderCounterDataPoint dataPoint = new LongAdderCounterDataPoint(StatUtils.LONG_INIT);

        assertThat(dataPoint.getAsLong()).isEqualTo(0L);

        // increment by zero
        dataPoint.increment(0L);
        assertThat(dataPoint.getAsLong()).isEqualTo(0L);

        // increment by one
        dataPoint.increment();
        assertThat(dataPoint.getAsLong()).isEqualTo(1L);

        // increment by custom value
        dataPoint.increment(7L);
        assertThat(dataPoint.getAsLong()).isEqualTo(8L);

        // test reset
        dataPoint.reset();
        assertThat(dataPoint.getAsLong()).isEqualTo(0L);
    }

    @Test
    void testDataPointWithNonZeroInitialValue() {
        LongAdderCounterDataPoint dataPoint = new LongAdderCounterDataPoint(() -> 3L);

        assertThat(dataPoint.getAsLong()).isEqualTo(3L);

        // increment by zero
        dataPoint.increment(0L);
        assertThat(dataPoint.getAsLong()).isEqualTo(3L);

        // increment by one
        dataPoint.increment();
        assertThat(dataPoint.getAsLong()).isEqualTo(4L);

        // increment by custom value
        dataPoint.increment(6L);
        assertThat(dataPoint.getAsLong()).isEqualTo(10L);

        // test reset
        dataPoint.reset();
        assertThat(dataPoint.getAsLong()).isEqualTo(3L);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -100L, Long.MIN_VALUE})
    void testIncrementNegativeValueThrows(long negativeValue) {
        LongAdderCounterDataPoint dataPoint = new LongAdderCounterDataPoint(StatUtils.LONG_INIT);
        assertThatThrownBy(() -> dataPoint.increment(negativeValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConcurrentIncrements() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 10000;
        LongAdderCounterDataPoint dataPoint = new LongAdderCounterDataPoint(StatUtils.LONG_INIT);

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                dataPoint.increment();
            }
        });

        assertThat(dataPoint.getAsLong()).isEqualTo(threadCount * incrementsPerThread);
    }
}
