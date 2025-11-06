// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class DoubleAdderCounterDataPointTest {

    @Test
    public void testNullInitializerThrows() {
        assertThatThrownBy(() -> new DoubleAdderCounterDataPoint(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -10.0, -100.0, Double.NEGATIVE_INFINITY})
    public void testNegativeInitializerThrows(double initValue) {
        assertThatThrownBy(() -> new DoubleAdderCounterDataPoint(() -> initValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Increment value must be non-negative");
    }

    @Test
    public void testDataPointWithZeroInitialValue() {
        DoubleAdderCounterDataPoint dataPoint = new DoubleAdderCounterDataPoint();

        assertThat(dataPoint.getAsDouble()).isEqualTo(0.0);

        // increment by zero
        dataPoint.increment(0.0);
        assertThat(dataPoint.getAsDouble()).isEqualTo(0.0);

        // increment by one
        dataPoint.increment();
        assertThat(dataPoint.getAsDouble()).isEqualTo(1.0);

        // increment by custom value
        dataPoint.increment(7.5);
        assertThat(dataPoint.getAsDouble()).isEqualTo(8.5);

        // test reset
        dataPoint.reset();
        assertThat(dataPoint.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    public void testDataPointWithNonZeroInitialValue() {
        DoubleAdderCounterDataPoint dataPoint = new DoubleAdderCounterDataPoint(() -> 3.5);

        assertThat(dataPoint.getAsDouble()).isEqualTo(3.5);

        // increment by zero
        dataPoint.increment(0.0);
        assertThat(dataPoint.getAsDouble()).isEqualTo(3.5);

        // increment by one
        dataPoint.increment();
        assertThat(dataPoint.getAsDouble()).isEqualTo(4.5);

        // increment by custom value
        dataPoint.increment(0.5);
        assertThat(dataPoint.getAsDouble()).isEqualTo(5.0);

        // test reset
        dataPoint.reset();
        assertThat(dataPoint.getAsDouble()).isEqualTo(3.5);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -10.0, -100.0, Double.NEGATIVE_INFINITY})
    public void testIncrementNegativeValueThrows(double negativeValue) {
        DoubleAdderCounterDataPoint dataPoint = new DoubleAdderCounterDataPoint();
        assertThatThrownBy(() -> dataPoint.increment(negativeValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testConcurrentIncrements() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 10000;
        DoubleAdderCounterDataPoint dataPoint = new DoubleAdderCounterDataPoint();

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                dataPoint.increment();
            }
        });

        assertThat(dataPoint.getAsDouble()).isEqualTo(threadCount * incrementsPerThread);
    }
}
