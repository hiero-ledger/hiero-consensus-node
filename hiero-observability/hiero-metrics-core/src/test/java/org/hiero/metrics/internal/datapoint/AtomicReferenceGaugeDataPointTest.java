// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class AtomicReferenceGaugeDataPointTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new AtomicReferenceGaugeDataPoint<>(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @Test
    void testDataPointNonNullInitialValue() {
        AtomicReferenceGaugeDataPoint<String> dataPoint = new AtomicReferenceGaugeDataPoint<>(() -> "initial");

        assertThat(dataPoint.get()).isEqualTo("initial");

        dataPoint.update(null);
        assertThat(dataPoint.get()).isNull();

        dataPoint.update("value1");
        assertThat(dataPoint.get()).isEqualTo("value1");

        dataPoint.reset();
        assertThat(dataPoint.get()).isEqualTo("initial");
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        AtomicReferenceGaugeDataPoint<String> dataPoint = new AtomicReferenceGaugeDataPoint<>(() -> null);

        final String[] threadValues = {"value1", "value2", "value3", "value4", "value5"};
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
            String updateValue = threadValues[threadIdx];
            for (int i = 0; i < updatesPerThread; i++) {
                dataPoint.update(updateValue);
            }
        });

        assertThat(dataPoint.get()).isIn(Arrays.asList(threadValues));
    }
}
