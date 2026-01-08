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

public class AtomicLongGaugeMeasurementTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new AtomicLongGaugeMeasurement(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
    void testInitializerSupplier(long initialValue) {
        AtomicLongGaugeMeasurement measurement = new AtomicLongGaugeMeasurement(() -> initialValue);

        assertThat(measurement.getInitValue()).isEqualTo(initialValue);
        assertThat(measurement.getAsLong()).isEqualTo(initialValue);

        measurement.update(42L);
        assertThat(measurement.getAsLong()).isEqualTo(42L);

        measurement.reset();
        assertThat(measurement.getAsLong()).isEqualTo(initialValue);

        measurement.update(17L);
        assertThat(measurement.getAndReset()).isEqualTo(17L);
        assertThat(measurement.getAsLong()).isEqualTo(initialValue);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        final AtomicLongGaugeMeasurement measurement = new AtomicLongGaugeMeasurement(StatUtils.LONG_INIT);

        final Long[] threadValues = {Long.MIN_VALUE, -100L, -14L, 0L, 14L, 100L, Long.MAX_VALUE};
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
            long updateValue = threadValues[threadIdx];
            for (int i = 0; i < updatesPerThread; i++) {
                measurement.update(updateValue);
            }
        });

        assertThat(measurement.getAsLong()).isIn(Arrays.asList(threadValues));
    }
}
