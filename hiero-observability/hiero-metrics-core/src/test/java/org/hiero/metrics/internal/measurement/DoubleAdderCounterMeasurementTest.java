// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class DoubleAdderCounterMeasurementTest {

    @Test
    void testNullInitializerThrows() {
        assertThatThrownBy(() -> new DoubleAdderCounterMeasurement(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -10.0, -100.0, Double.NEGATIVE_INFINITY})
    void testNegativeInitializerThrows(double initValue) {
        assertThatThrownBy(() -> new DoubleAdderCounterMeasurement(() -> initValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Increment value must be non-negative");
    }

    @Test
    void testMeasurementWithZeroInitialValue() {
        DoubleAdderCounterMeasurement measurement = new DoubleAdderCounterMeasurement();

        assertThat(measurement.getAsDouble()).isEqualTo(0.0);

        // increment by zero
        measurement.increment(0.0);
        assertThat(measurement.getAsDouble()).isEqualTo(0.0);

        // increment by one
        measurement.increment();
        assertThat(measurement.getAsDouble()).isEqualTo(1.0);

        // increment by custom value
        measurement.increment(7.5);
        assertThat(measurement.getAsDouble()).isEqualTo(8.5);

        // test reset
        measurement.reset();
        assertThat(measurement.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testMeasurementWithNonZeroInitialValue() {
        DoubleAdderCounterMeasurement measurement = new DoubleAdderCounterMeasurement(() -> 3.5);

        assertThat(measurement.getAsDouble()).isEqualTo(3.5);

        // increment by zero
        measurement.increment(0.0);
        assertThat(measurement.getAsDouble()).isEqualTo(3.5);

        // increment by one
        measurement.increment();
        assertThat(measurement.getAsDouble()).isEqualTo(4.5);

        // increment by custom value
        measurement.increment(0.5);
        assertThat(measurement.getAsDouble()).isEqualTo(5.0);

        // test reset
        measurement.reset();
        assertThat(measurement.getAsDouble()).isEqualTo(3.5);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -10.0, -100.0, Double.NEGATIVE_INFINITY})
    void testIncrementNegativeValueThrows(double negativeValue) {
        final DoubleAdderCounterMeasurement measurement = new DoubleAdderCounterMeasurement();
        assertThatThrownBy(() -> measurement.increment(negativeValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConcurrentIncrements() throws InterruptedException {
        final int threadCount = 10;
        final int incrementsPerThread = 10000;
        final DoubleAdderCounterMeasurement measurement = new DoubleAdderCounterMeasurement();

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                measurement.increment();
            }
        });

        assertThat(measurement.getAsDouble()).isEqualTo(threadCount * incrementsPerThread);
    }
}
