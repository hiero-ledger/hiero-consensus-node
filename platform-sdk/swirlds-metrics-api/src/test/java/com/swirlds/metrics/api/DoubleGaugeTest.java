// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.DataType.FLOAT;
import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("Testing DoubleGauge")
class DoubleGaugeTest {

    private DoubleGauge sut;

    @BeforeEach
    void setup() {
        sut = Mockito.mock(DoubleGauge.class);
        when(sut.get(Mockito.any())).thenCallRealMethod();
        when(sut.getMetricType()).thenCallRealMethod();
        when(sut.getDataType()).thenCallRealMethod();
        when(sut.getValueTypes()).thenCallRealMethod();
    }

    @Test
    void getMetricType() {
        assertThat(sut.getMetricType()).isEqualTo(MetricType.GAUGE);
    }

    @Test
    void getDataType() {
        assertThat(sut.getDataType()).isEqualTo(FLOAT);
    }

    @Test
    void getValueTypes() {
        assertThat(sut.getValueTypes()).containsExactly(VALUE);
    }

    @Test
    void get_ShouldReturnValueByValueType() {
        final Double value = sut.get(VALUE);

        assertThat(value).isEqualTo(sut.get());
        verify(sut, times(2)).get();
    }

    @Test
    void get_ShouldThrowExceptionIfValueTypeNotSupported() {
        assertThatThrownBy(() -> sut.get(MAX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sut.get(MIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sut.get(STD_DEV)).isInstanceOf(IllegalArgumentException.class);
    }
}
