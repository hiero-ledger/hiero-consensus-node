// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.junit.jupiter.api.Test;

public class GenericGaugeTest
        extends AbstractSettableMetricBaseTest<GenericGauge<Duration>, GenericGauge.Builder<Duration>> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<GenericGauge<Duration>> metricClassType() {
        return (Class<GenericGauge<Duration>>) (Class<?>) GenericGauge.class;
    }

    @Override
    protected GenericGauge.Builder<Duration> emptyMetricBuilder(String name) {
        return GenericGauge.builder(name, GenericGauge.durationToLongFunction(ChronoUnit.SECONDS));
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> GenericGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        MetricKey<GenericGauge<Duration>> metricKey = null;
        assertThatThrownBy(
                        () -> GenericGauge.builder(metricKey, GenericGauge.durationToLongFunction(ChronoUnit.SECONDS)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullConverterBuilder() {
        assertThatThrownBy(() -> GenericGauge.builder("test", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value converter cannot be null");
    }
}
