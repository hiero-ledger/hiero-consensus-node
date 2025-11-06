// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import java.util.Arrays;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AtomicLongGaugeDataPointTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new AtomicLongGaugeDataPoint(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
    void testInitializerSupplier(long initialValue) {
        AtomicLongGaugeDataPoint dataPoint = new AtomicLongGaugeDataPoint(() -> initialValue);

        assertThat(dataPoint.getInitValue()).isEqualTo(initialValue);
        assertThat(dataPoint.getAsLong()).isEqualTo(initialValue);

        dataPoint.update(42L);
        assertThat(dataPoint.getAsLong()).isEqualTo(42L);

        dataPoint.reset();
        assertThat(dataPoint.getAsLong()).isEqualTo(initialValue);

        dataPoint.update(17L);
        assertThat(dataPoint.getAndReset()).isEqualTo(17L);
        assertThat(dataPoint.getAsLong()).isEqualTo(initialValue);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        AtomicLongGaugeDataPoint dataPoint = new AtomicLongGaugeDataPoint(StatUtils.LONG_INIT);

        final Long[] threadValues = {Long.MIN_VALUE, -100L, -14L, 0L, 14L, 100L, Long.MAX_VALUE};
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
            long updateValue = threadValues[threadIdx];
            for (int i = 0; i < updatesPerThread; i++) {
                dataPoint.update(updateValue);
            }
        });

        assertThat(dataPoint.getAsLong()).isIn(Arrays.asList(threadValues));
    }
}
