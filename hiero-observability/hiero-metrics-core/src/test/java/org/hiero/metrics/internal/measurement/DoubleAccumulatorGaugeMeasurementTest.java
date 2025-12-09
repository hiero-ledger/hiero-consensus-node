// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;

public class DoubleAccumulatorGaugeMeasurementTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new DoubleAccumulatorGaugeMeasurement(StatUtils.DOUBLE_SUM, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @Test
    void testNullOperator() {
        assertThatThrownBy(() -> new DoubleAccumulatorGaugeMeasurement(null, StatUtils.DOUBLE_INIT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("operator must not be null");
    }

    @Test
    void testMeasurementWithMinOperatorAndDefaultInitializer() {
        DoubleAccumulatorGaugeMeasurement measurement =
                new DoubleAccumulatorGaugeMeasurement(StatUtils.DOUBLE_MIN, StatUtils.DOUBLE_INIT);

        assertThat(measurement.getAsDouble()).isEqualTo(0.0);

        measurement.update(1.5);
        assertThat(measurement.getAsDouble()).isEqualTo(0.0);

        measurement.update(-1.5);
        assertThat(measurement.getAsDouble()).isEqualTo(-1.5);

        measurement.update(-1.2);
        assertThat(measurement.getAsDouble()).isEqualTo(-1.5);

        measurement.reset();
        assertThat(measurement.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testMeasurementWithMaxOperatorAndCustomInitializer() {
        DoubleAccumulatorGaugeMeasurement measurement =
                new DoubleAccumulatorGaugeMeasurement(StatUtils.DOUBLE_MAX, () -> -1.0);

        assertThat(measurement.getAsDouble()).isEqualTo(-1.0);

        measurement.update(-0.5);
        assertThat(measurement.getAsDouble()).isEqualTo(-0.5);

        measurement.update(-0.8);
        assertThat(measurement.getAsDouble()).isEqualTo(-0.5);

        measurement.update(1.8);
        assertThat(measurement.getAsDouble()).isEqualTo(1.8);

        measurement.reset();
        assertThat(measurement.getAsDouble()).isEqualTo(-1.0);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        final DoubleAccumulatorGaugeMeasurement measurement =
                new DoubleAccumulatorGaugeMeasurement(StatUtils.DOUBLE_MAX, StatUtils.DOUBLE_INIT);

        final int threadCount = 10;
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < updatesPerThread; i++) {
                measurement.update(threadIdx);
            }
        });

        assertThat(measurement.getAsDouble()).isEqualTo(threadCount - 1);
    }
}
