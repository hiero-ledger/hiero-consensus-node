// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.junit.jupiter.api.Test;

public class ObservableGaugeTest extends AbstractMetricBaseTest<ObservableGauge, ObservableGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected Class<ObservableGauge> metricClassType() {
        return ObservableGauge.class;
    }

    @Override
    protected ObservableGauge.Builder emptyMetricBuilder(String name) {
        return ObservableGauge.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> ObservableGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> ObservableGauge.builder((MetricKey<ObservableGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> ObservableGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }
}
