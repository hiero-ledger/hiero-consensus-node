// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import java.util.Arrays;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AtomicDoubleGaugeMeasurementTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new AtomicDoubleGaugeMeasurement(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NEGATIVE_INFINITY, -1.5, 0.0, 1.5, Double.POSITIVE_INFINITY})
    void testInitializerSupplier(double initialValue) {
        AtomicDoubleGaugeMeasurement measurement = new AtomicDoubleGaugeMeasurement(() -> initialValue);

        assertThat(measurement.getInitValue()).isEqualTo(initialValue);
        assertThat(measurement.getAsDouble()).isEqualTo(initialValue);

        measurement.update(42.5);
        assertThat(measurement.getAsDouble()).isEqualTo(42.5);

        measurement.reset();
        assertThat(measurement.getAsDouble()).isEqualTo(initialValue);

        measurement.update(17.5);
        assertThat(measurement.getAndReset()).isEqualTo(17.5);
        assertThat(measurement.getAsDouble()).isEqualTo(initialValue);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        AtomicDoubleGaugeMeasurement measurement = new AtomicDoubleGaugeMeasurement(StatUtils.DOUBLE_INIT);

        final Double[] threadValues = {
            Double.NEGATIVE_INFINITY, -100.5, -14.2, 0.0, 14.2, 100.5, Double.POSITIVE_INFINITY
        };
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
            double updateValue = threadValues[threadIdx];
            for (int i = 0; i < updatesPerThread; i++) {
                measurement.update(updateValue);
            }
        });

        assertThat(measurement.getAsDouble()).isIn(Arrays.asList(threadValues));
    }
}
