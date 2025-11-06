// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;

public class LongAccumulatorGaugeDataPointTest {

    @Test
    public void testNullInitializer() {
        assertThatThrownBy(() -> new LongAccumulatorGaugeDataPoint(StatUtils.LONG_SUM, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @Test
    public void testNullOperator() {
        assertThatThrownBy(() -> new LongAccumulatorGaugeDataPoint(null, StatUtils.LONG_INIT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("operator must not be null");
    }

    @Test
    public void testDataPointWithMinOperatorAndDefaultInitializer() {
        LongAccumulatorGaugeDataPoint dataPoint =
                new LongAccumulatorGaugeDataPoint(StatUtils.LONG_MIN, StatUtils.LONG_INIT);

        assertThat(dataPoint.getAsLong()).isEqualTo(0L);

        dataPoint.update(1L);
        assertThat(dataPoint.getAsLong()).isEqualTo(0L);

        dataPoint.update(-1L);
        assertThat(dataPoint.getAsLong()).isEqualTo(-1L);

        dataPoint.update(-2L);
        assertThat(dataPoint.getAsLong()).isEqualTo(-2L);

        dataPoint.reset();
        assertThat(dataPoint.getAsLong()).isEqualTo(0L);
    }

    @Test
    public void testDataPointWithMaxOperatorAndCustomInitializer() {
        LongAccumulatorGaugeDataPoint dataPoint = new LongAccumulatorGaugeDataPoint(StatUtils.LONG_MAX, () -> -1L);

        assertThat(dataPoint.getAsLong()).isEqualTo(-1L);

        dataPoint.update(-2L);
        assertThat(dataPoint.getAsLong()).isEqualTo(-1L);

        dataPoint.update(1L);
        assertThat(dataPoint.getAsLong()).isEqualTo(1L);

        dataPoint.update(10L);
        assertThat(dataPoint.getAsLong()).isEqualTo(10L);

        dataPoint.reset();
        assertThat(dataPoint.getAsLong()).isEqualTo(-1L);
    }

    @Test
    public void testConcurrentUpdates() throws InterruptedException {
        LongAccumulatorGaugeDataPoint dataPoint =
                new LongAccumulatorGaugeDataPoint(StatUtils.LONG_MAX, StatUtils.LONG_INIT);

        final int threadCount = 10;
        final int updatesPerThread = 10000;

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < updatesPerThread; i++) {
                dataPoint.update(threadIdx);
            }
        });

        assertThat(dataPoint.getAsLong()).isEqualTo(threadCount - 1);
    }
}
