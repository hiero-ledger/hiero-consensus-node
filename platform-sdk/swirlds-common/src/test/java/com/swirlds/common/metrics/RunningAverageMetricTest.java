// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.metrics.api.Metric.DataType.FLOAT;
import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.swirlds.metrics.api.MetricType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("Testing RunningAverageMetric")
class RunningAverageMetricTest {

    private RunningAverageMetric sut;

    @BeforeEach
    void setup() {
        sut = Mockito.mock(RunningAverageMetric.class);
        when(sut.getMetricType()).thenCallRealMethod();
        when(sut.getDataType()).thenCallRealMethod();
        when(sut.getValueTypes()).thenCallRealMethod();
    }

    @Test
    void getMetricType() {
        assertThat(sut.getMetricType()).isEqualTo(MetricType.RUNNING_AVERAGE);
    }

    @Test
    void getDataType() {
        assertThat(sut.getDataType()).isEqualTo(FLOAT);
    }

    @Test
    void getValueTypes() {
        assertThat(sut.getValueTypes()).containsExactly(VALUE, MAX, MIN, STD_DEV);
    }
}
