// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.junit.jupiter.api.Test;

public class StatelessMetricTest extends AbstractMetricBaseTest<StatelessMetric, StatelessMetric.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected Class<StatelessMetric> metricClassType() {
        return StatelessMetric.class;
    }

    @Override
    protected StatelessMetric.Builder emptyMetricBuilder(String name) {
        return StatelessMetric.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> StatelessMetric.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> StatelessMetric.builder((MetricKey<StatelessMetric>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> StatelessMetric.builder((String) null)).isInstanceOf(NullPointerException.class);
    }
}
