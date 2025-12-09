// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AtomicLongCounterMeasurementTest {

    @Test
    void testNullInitializerThrows() {
        assertThatThrownBy(() -> new AtomicLongCounterMeasurement(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -100L, Long.MIN_VALUE})
    void testNegativeInitializerThrows(long initValue) {
        assertThatThrownBy(() -> new AtomicLongCounterMeasurement(() -> initValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Increment value must be non-negative");
    }

    @Test
    void testMeasurementWithZeroInitialValue() {
        AtomicLongCounterMeasurement measurement = new AtomicLongCounterMeasurement();

        assertThat(measurement.getAsLong()).isEqualTo(0L);

        // increment by zero
        measurement.increment(0L);
        assertThat(measurement.getAsLong()).isEqualTo(0L);

        // increment by one
        measurement.increment();
        assertThat(measurement.getAsLong()).isEqualTo(1L);

        // increment by custom value
        measurement.increment(10L);
        assertThat(measurement.getAsLong()).isEqualTo(11L);

        // test reset
        measurement.reset();
        assertThat(measurement.getAsLong()).isEqualTo(0L);
    }

    @Test
    void testMeasurementWithNonZeroInitialValue() {
        AtomicLongCounterMeasurement measurement = new AtomicLongCounterMeasurement(() -> 5L);

        assertThat(measurement.getAsLong()).isEqualTo(5L);

        // increment by zero
        measurement.increment(0L);
        assertThat(measurement.getAsLong()).isEqualTo(5L);

        // increment by one
        measurement.increment();
        assertThat(measurement.getAsLong()).isEqualTo(6L);

        // increment by custom value
        measurement.increment(10L);
        assertThat(measurement.getAsLong()).isEqualTo(16L);

        // test reset
        measurement.reset();
        assertThat(measurement.getAsLong()).isEqualTo(5L);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -100L, Long.MIN_VALUE})
    void testIncrementNegativeValueThrows(long negativeValue) {
        final AtomicLongCounterMeasurement measurement = new AtomicLongCounterMeasurement();
        assertThatThrownBy(() -> measurement.increment(negativeValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConcurrentIncrements() throws InterruptedException {
        final int threadCount = 10;
        final int incrementsPerThread = 1000;
        final AtomicLongCounterMeasurement measurement = new AtomicLongCounterMeasurement();

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int j = 0; j < incrementsPerThread; j++) {
                measurement.increment();
            }
        });

        assertThat(measurement.getAsLong()).isEqualTo(threadCount * incrementsPerThread);
    }
}
