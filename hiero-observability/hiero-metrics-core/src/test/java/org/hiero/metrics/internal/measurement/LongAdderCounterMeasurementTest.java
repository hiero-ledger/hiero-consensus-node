// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LongAdderCounterMeasurementTest {

    @Test
    void testNullInitializerThrows() {
        assertThatThrownBy(() -> new LongAdderCounterMeasurement(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -10L, Long.MIN_VALUE})
    void testNegativeInitializerThrows(long initValue) {
        assertThatThrownBy(() -> new LongAdderCounterMeasurement(() -> initValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Increment value must be non-negative");
    }

    @Test
    void testMeasurementWithZeroInitialValue() {
        LongAdderCounterMeasurement measurement = new LongAdderCounterMeasurement(StatUtils.LONG_INIT);

        assertThat(measurement.getAsLong()).isEqualTo(0L);

        // increment by zero
        measurement.increment(0L);
        assertThat(measurement.getAsLong()).isEqualTo(0L);

        // increment by one
        measurement.increment();
        assertThat(measurement.getAsLong()).isEqualTo(1L);

        // increment by custom value
        measurement.increment(7L);
        assertThat(measurement.getAsLong()).isEqualTo(8L);

        // test reset
        measurement.reset();
        assertThat(measurement.getAsLong()).isEqualTo(0L);
    }

    @Test
    void testMeasurementWithNonZeroInitialValue() {
        LongAdderCounterMeasurement measurement = new LongAdderCounterMeasurement(() -> 3L);

        assertThat(measurement.getAsLong()).isEqualTo(3L);

        // increment by zero
        measurement.increment(0L);
        assertThat(measurement.getAsLong()).isEqualTo(3L);

        // increment by one
        measurement.increment();
        assertThat(measurement.getAsLong()).isEqualTo(4L);

        // increment by custom value
        measurement.increment(6L);
        assertThat(measurement.getAsLong()).isEqualTo(10L);

        // test reset
        measurement.reset();
        assertThat(measurement.getAsLong()).isEqualTo(3L);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -10L, -100L, Long.MIN_VALUE})
    void testIncrementNegativeValueThrows(long negativeValue) {
        final LongAdderCounterMeasurement measurement = new LongAdderCounterMeasurement(StatUtils.LONG_INIT);
        assertThatThrownBy(() -> measurement.increment(negativeValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConcurrentIncrements() throws InterruptedException {
        final int threadCount = 10;
        final int incrementsPerThread = 10000;
        final LongAdderCounterMeasurement measurement = new LongAdderCounterMeasurement(StatUtils.LONG_INIT);

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                measurement.increment();
            }
        });

        assertThat(measurement.getAsLong()).isEqualTo(threadCount * incrementsPerThread);
    }
}
