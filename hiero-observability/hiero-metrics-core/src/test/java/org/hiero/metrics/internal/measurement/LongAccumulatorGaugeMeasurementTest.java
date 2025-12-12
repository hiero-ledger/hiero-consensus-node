// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;

public class LongAccumulatorGaugeMeasurementTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new LongAccumulatorGaugeMeasurement(StatUtils.LONG_SUM, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @Test
    void testNullOperator() {
        assertThatThrownBy(() -> new LongAccumulatorGaugeMeasurement(null, StatUtils.LONG_INIT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("operator must not be null");
    }

    @Test
    void testMeasurementWithMinOperatorAndDefaultInitializer() {
        LongAccumulatorGaugeMeasurement measurement =
                new LongAccumulatorGaugeMeasurement(StatUtils.LONG_MIN, StatUtils.LONG_INIT);

        assertThat(measurement.getAsLong()).isEqualTo(0L);

        measurement.update(1L);
        assertThat(measurement.getAsLong()).isEqualTo(0L);

        measurement.update(-1L);
        assertThat(measurement.getAsLong()).isEqualTo(-1L);

        measurement.update(-2L);
        assertThat(measurement.getAsLong()).isEqualTo(-2L);

        measurement.reset();
        assertThat(measurement.getAsLong()).isEqualTo(0L);
    }

    @Test
    void testMeasurementWithMaxOperatorAndCustomInitializer() {
        LongAccumulatorGaugeMeasurement measurement =
                new LongAccumulatorGaugeMeasurement(StatUtils.LONG_MAX, () -> -1L);

        assertThat(measurement.getAsLong()).isEqualTo(-1L);

        measurement.update(-2L);
        assertThat(measurement.getAsLong()).isEqualTo(-1L);

        measurement.update(1L);
        assertThat(measurement.getAsLong()).isEqualTo(1L);

        measurement.update(10L);
        assertThat(measurement.getAsLong()).isEqualTo(10L);

        measurement.reset();
        assertThat(measurement.getAsLong()).isEqualTo(-1L);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        final LongAccumulatorGaugeMeasurement measurement =
                new LongAccumulatorGaugeMeasurement(StatUtils.LONG_MAX, StatUtils.LONG_INIT);

        final int threadCount = 10;
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < updatesPerThread; i++) {
                measurement.update(threadIdx);
            }
        });

        assertThat(measurement.getAsLong()).isEqualTo(threadCount - 1);
    }
}
