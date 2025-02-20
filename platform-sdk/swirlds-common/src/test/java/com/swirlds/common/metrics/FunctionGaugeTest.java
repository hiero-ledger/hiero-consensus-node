// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.metrics.api.MetricType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("Testing FunctionGauge")
class FunctionGaugeTest {

    private FunctionGauge<Double> sut;

    @BeforeEach
    void setup() {
        sut = Mockito.mock(FunctionGauge.class);
        when(sut.get(Mockito.any())).thenCallRealMethod();
        when(sut.getMetricType()).thenCallRealMethod();
        when(sut.getValueTypes()).thenCallRealMethod();
    }

    @Test
    void getMetricType() {
        assertThat(sut.getMetricType()).isEqualTo(MetricType.GAUGE);
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
