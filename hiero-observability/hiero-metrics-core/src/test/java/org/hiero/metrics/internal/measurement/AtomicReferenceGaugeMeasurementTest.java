// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class AtomicReferenceGaugeMeasurementTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new AtomicReferenceGaugeMeasurement<>(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @Test
    void testMeasurementNonNullInitialValue() {
        AtomicReferenceGaugeMeasurement<String> measurement = new AtomicReferenceGaugeMeasurement<>(() -> "initial");

        assertThat(measurement.get()).isEqualTo("initial");

        measurement.update(null);
        assertThat(measurement.get()).isNull();

        measurement.update("value1");
        assertThat(measurement.get()).isEqualTo("value1");

        measurement.reset();
        assertThat(measurement.get()).isEqualTo("initial");
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        final AtomicReferenceGaugeMeasurement<String> measurement = new AtomicReferenceGaugeMeasurement<>(() -> null);

        final String[] threadValues = {"value1", "value2", "value3", "value4", "value5"};
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
            String updateValue = threadValues[threadIdx];
            for (int i = 0; i < updatesPerThread; i++) {
                measurement.update(updateValue);
            }
        });

        assertThat(measurement.get()).isIn(Arrays.asList(threadValues));
    }
}
