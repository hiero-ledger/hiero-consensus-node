// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.time.Time;
import java.util.stream.Stream;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class FrequencyCumulativeAvgTest {

    @Test
    void testCreateMetricKey() {
        MetricKey<GaugeAdapter<FrequencyCumulativeAvg>> key = FrequencyCumulativeAvg.key("testMetric");

        assertThat(key.name()).isEqualTo("testMetric");
        assertThat(key.type()).isEqualTo(GaugeAdapter.class);
    }

    @Test
    void testNullTimeProviderThrows() {
        assertThatThrownBy(() -> new FrequencyCumulativeAvg(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("time provider cannot be null");
    }

    @Test
    void testUpdate() {
        Time time = mock(Time.class);
        when(time.currentTimeMillis()).thenReturn(0L, 200L, 4000L);

        FrequencyCumulativeAvg stat = new FrequencyCumulativeAvg(time);

        stat.count(4);
        stat.count();

        assertThat(stat.getAsDouble()).isEqualTo(25.0);

        stat.count(5);
        assertThat(stat.getAsDouble()).isEqualTo(2.5);
    }

    @Test
    void testReset() {
        FrequencyCumulativeAvg stat = new FrequencyCumulativeAvg();

        stat.count(10);
        stat.reset();

        assertThat(stat.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testGetReset() {
        Time time = mock(Time.class);
        when(time.currentTimeMillis()).thenReturn(0L, 1000L);

        FrequencyCumulativeAvg stat = new FrequencyCumulativeAvg(time);

        stat.count(10);

        assertThat(stat.getAndReset()).isEqualTo(10.0);
        assertThat(stat.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testMetricBuilder() {
        Time time = mock(Time.class);
        when(time.currentTimeMillis()).thenReturn(0L, 1000L);

        GaugeAdapter.Builder<FrequencyCumulativeAvg> builder =
                FrequencyCumulativeAvg.metricBuilder(time, FrequencyCumulativeAvg.key("testMetric"));

        assertThat(builder).isNotNull();

        GaugeAdapter<FrequencyCumulativeAvg> metric = builder.build();

        metric.getOrCreateNotLabeled().count(10);

        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(10.0);
    }

    @ParameterizedTest
    @MethodSource("intOverflowTimeProvider")
    void testTimeOverflowHandling(long time1, long time2) {
        Time time = mock(Time.class);
        when(time.currentTimeMillis()).thenReturn(time1, time2);

        FrequencyCumulativeAvg stat = new FrequencyCumulativeAvg(time);

        stat.count(10);

        assertThat(stat.getAsDouble()).isEqualTo(5.0);
    }

    private static Stream<Arguments> intOverflowTimeProvider() {
        return Stream.of(
                Arguments.of((long) (Integer.MAX_VALUE - 1000), ((long) Integer.MAX_VALUE) + 1000L),
                Arguments.of(Long.MAX_VALUE - 3000L, Long.MAX_VALUE - 1000L));
    }
}
