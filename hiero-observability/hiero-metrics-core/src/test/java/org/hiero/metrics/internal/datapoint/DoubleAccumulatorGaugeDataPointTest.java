// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;

public class DoubleAccumulatorGaugeDataPointTest {

    @Test
    void testNullInitializer() {
        assertThatThrownBy(() -> new DoubleAccumulatorGaugeDataPoint(StatUtils.DOUBLE_SUM, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @Test
    void testNullOperator() {
        assertThatThrownBy(() -> new DoubleAccumulatorGaugeDataPoint(null, StatUtils.DOUBLE_INIT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("operator must not be null");
    }

    @Test
    void testDataPointWithMinOperatorAndDefaultInitializer() {
        DoubleAccumulatorGaugeDataPoint dataPoint =
                new DoubleAccumulatorGaugeDataPoint(StatUtils.DOUBLE_MIN, StatUtils.DOUBLE_INIT);

        assertThat(dataPoint.getAsDouble()).isEqualTo(0.0);

        dataPoint.update(1.5);
        assertThat(dataPoint.getAsDouble()).isEqualTo(0.0);

        dataPoint.update(-1.5);
        assertThat(dataPoint.getAsDouble()).isEqualTo(-1.5);

        dataPoint.update(-1.2);
        assertThat(dataPoint.getAsDouble()).isEqualTo(-1.5);

        dataPoint.reset();
        assertThat(dataPoint.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testDataPointWithMaxOperatorAndCustomInitializer() {
        DoubleAccumulatorGaugeDataPoint dataPoint =
                new DoubleAccumulatorGaugeDataPoint(StatUtils.DOUBLE_MAX, () -> -1.0);

        assertThat(dataPoint.getAsDouble()).isEqualTo(-1.0);

        dataPoint.update(-0.5);
        assertThat(dataPoint.getAsDouble()).isEqualTo(-0.5);

        dataPoint.update(-0.8);
        assertThat(dataPoint.getAsDouble()).isEqualTo(-0.5);

        dataPoint.update(1.8);
        assertThat(dataPoint.getAsDouble()).isEqualTo(1.8);

        dataPoint.reset();
        assertThat(dataPoint.getAsDouble()).isEqualTo(-1.0);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        DoubleAccumulatorGaugeDataPoint dataPoint =
                new DoubleAccumulatorGaugeDataPoint(StatUtils.DOUBLE_MAX, StatUtils.DOUBLE_INIT);

        final int threadCount = 10;
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < updatesPerThread; i++) {
                dataPoint.update(threadIdx);
            }
        });

        assertThat(dataPoint.getAsDouble()).isEqualTo(threadCount - 1);
    }
}
