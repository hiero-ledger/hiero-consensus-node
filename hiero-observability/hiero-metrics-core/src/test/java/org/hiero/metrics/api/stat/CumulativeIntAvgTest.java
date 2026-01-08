// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.junit.jupiter.api.Test;

public class CumulativeIntAvgTest {

    @Test
    void testCreateMetricKey() {
        MetricKey<GaugeAdapter<CumulativeIntAvg>> key = CumulativeIntAvg.key("testMetric");

        assertThat(key.name()).isEqualTo("testMetric");
        assertThat(key.type()).isEqualTo(GaugeAdapter.class);
    }

    @Test
    void testUpdate() {
        CumulativeIntAvg stat = new CumulativeIntAvg();

        stat.update(10);
        stat.update(20);
        stat.update(30);
        assertThat(stat.getAsDouble()).isEqualTo(20.0);

        stat.update(10);
        assertThat(stat.getAsDouble()).isEqualTo(17.5);
    }

    @Test
    void testReset() {
        CumulativeIntAvg stat = new CumulativeIntAvg();

        stat.update(10);
        stat.reset();

        assertThat(stat.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testGetReset() {
        CumulativeIntAvg stat = new CumulativeIntAvg();

        stat.update(10);

        assertThat(stat.getAndReset()).isEqualTo(10.0);
        assertThat(stat.getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testMetricBuilder() {
        GaugeAdapter.Builder<CumulativeIntAvg> builder = CumulativeIntAvg.metricBuilder("testMetric");

        assertThat(builder).isNotNull();

        GaugeAdapter<CumulativeIntAvg> metric = builder.build();

        metric.getOrCreateNotLabeled().update(10);
        metric.getOrCreateNotLabeled().update(20);

        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(15.0);
    }
}
