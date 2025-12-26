// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.stream.Stream;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricKey;
import org.junit.jupiter.api.Test;

public class JvmMetricsRegistrationTest {

    @Test
    void testJvmMetricsRegistration() {
        Collection<Metric.Builder<?, ?>> metricsToRegister = new JvmMetricsRegistration().getMetricsToRegister(null);
        Stream<String> metricNames =
                metricsToRegister.stream().map(Metric.Builder::key).map(MetricKey::name);

        assertThat(metricNames).contains("jvm:memory", "jvm:available_processors");
    }
}
