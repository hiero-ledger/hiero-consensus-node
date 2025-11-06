// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AtomicLongCounterDataPointTest {

    @Test
    public void testNullInitializerThrows() {
        assertThatThrownBy(() -> new AtomicLongCounterDataPoint(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -100L, Long.MIN_VALUE})
    public void testNegativeInitializerThrows(long initValue) {
        assertThatThrownBy(() -> new AtomicLongCounterDataPoint(() -> initValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Increment value must be non-negative");
    }

    @Test
    public void testDataPointWithZeroInitialValue() {
        AtomicLongCounterDataPoint dataPoint = new AtomicLongCounterDataPoint();

        assertThat(dataPoint.getAsLong()).isEqualTo(0L);

        // increment by zero
        dataPoint.increment(0L);
        assertThat(dataPoint.getAsLong()).isEqualTo(0L);

        // increment by one
        dataPoint.increment();
        assertThat(dataPoint.getAsLong()).isEqualTo(1L);

        // increment by custom value
        dataPoint.increment(10L);
        assertThat(dataPoint.getAsLong()).isEqualTo(11L);

        // test reset
        dataPoint.reset();
        assertThat(dataPoint.getAsLong()).isEqualTo(0L);
    }

    @Test
    public void testDataPointWithNonZeroInitialValue() {
        AtomicLongCounterDataPoint dataPoint = new AtomicLongCounterDataPoint(() -> 5L);

        assertThat(dataPoint.getAsLong()).isEqualTo(5L);

        // increment by zero
        dataPoint.increment(0L);
        assertThat(dataPoint.getAsLong()).isEqualTo(5L);

        // increment by one
        dataPoint.increment();
        assertThat(dataPoint.getAsLong()).isEqualTo(6L);

        // increment by custom value
        dataPoint.increment(10L);
        assertThat(dataPoint.getAsLong()).isEqualTo(16L);

        // test reset
        dataPoint.reset();
        assertThat(dataPoint.getAsLong()).isEqualTo(5L);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -100L, Long.MIN_VALUE})
    public void testIncrementNegativeValueThrows(long negativeValue) {
        AtomicLongCounterDataPoint dataPoint = new AtomicLongCounterDataPoint();
        assertThatThrownBy(() -> dataPoint.increment(negativeValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testConcurrentIncrements() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 1000;
        AtomicLongCounterDataPoint dataPoint = new AtomicLongCounterDataPoint();

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int j = 0; j < incrementsPerThread; j++) {
                dataPoint.increment();
            }
        });

        assertThat(dataPoint.getAsLong()).isEqualTo(threadCount * incrementsPerThread);
    }
}
