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

public class AtomicDoubleGaugeDataPointTest {

    @Test
    public void testNullInitializer() {
        assertThatThrownBy(() -> new AtomicDoubleGaugeDataPoint(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NEGATIVE_INFINITY, -1.5, 0.0, 1.5, Double.POSITIVE_INFINITY})
    public void testInitializerSupplier(double initialValue) {
        AtomicDoubleGaugeDataPoint dataPoint = new AtomicDoubleGaugeDataPoint(() -> initialValue);

        assertThat(dataPoint.getAsDouble()).isEqualTo(initialValue);

        dataPoint.update(42.5);
        assertThat(dataPoint.getAsDouble()).isEqualTo(42.5);

        dataPoint.reset();
        assertThat(dataPoint.getAsDouble()).isEqualTo(initialValue);

        dataPoint.update(17.5);
        assertThat(dataPoint.getAndReset()).isEqualTo(17.5);
        assertThat(dataPoint.getAsDouble()).isEqualTo(initialValue);
    }

    @Test
    public void testConcurrentUpdates() throws InterruptedException {
        AtomicDoubleGaugeDataPoint dataPoint = new AtomicDoubleGaugeDataPoint(StatUtils.DOUBLE_INIT);

        final Double[] threadValues = {
            Double.NEGATIVE_INFINITY, -100.5, -14.2, 0.0, 14.2, 100.5, Double.POSITIVE_INFINITY
        };
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
            double updateValue = threadValues[threadIdx];
            for (int i = 0; i < updatesPerThread; i++) {
                dataPoint.update(updateValue);
            }
        });

        assertThat(dataPoint.getAsDouble()).isIn(Arrays.asList(threadValues));
    }
}
