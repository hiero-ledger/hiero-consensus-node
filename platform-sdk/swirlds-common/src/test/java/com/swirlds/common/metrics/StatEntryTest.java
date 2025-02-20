// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.MetricType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("Testing StatEntry")
class StatEntryTest {

    private StatEntry sut;
    private StatEntry sutWithStatsBuffered;

    @BeforeEach
    void setup() {
        sut = Mockito.mock(StatEntry.class);
        when(sut.get(Mockito.any())).thenCallRealMethod();
        when(sut.getMetricType()).thenCallRealMethod();
        when(sut.getValueTypes()).thenCallRealMethod();
        sutWithStatsBuffered = Mockito.mock(StatEntry.class);
        when(sutWithStatsBuffered.getBuffered()).thenReturn(mock(StatsBuffered.class));
        when(sutWithStatsBuffered.getValueTypes()).thenCallRealMethod();
    }

    @Test
    void getMetricType() {
        assertThat(sut.getMetricType()).isEqualTo(MetricType.STAT_ENTRY);
    }

    @Test
    void getValueTypes_ShouldHaveVALUE_WithoutStatsBuffered() {
        assertThat(sut.getValueTypes()).containsExactly(VALUE);
    }

    @Test
    void getValueTypes_ShouldReturnAllTypes_WithStatsBuffered() {
        assertThat(sutWithStatsBuffered.getValueTypes()).containsExactly(VALUE, MAX, MIN, STD_DEV);
    }
}
