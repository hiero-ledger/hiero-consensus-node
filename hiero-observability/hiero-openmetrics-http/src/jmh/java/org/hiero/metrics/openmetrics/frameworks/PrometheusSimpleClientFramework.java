// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.frameworks;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.SimpleCollector;
import java.util.UUID;
import java.util.function.BiFunction;
import org.hiero.metrics.test.fixtures.framework.DataPointUpdater;
import org.hiero.metrics.test.fixtures.framework.MetricAdapter;
import org.hiero.metrics.test.fixtures.framework.MetricFramework;
import org.hiero.metrics.test.fixtures.framework.MetricType;

/**
 * {@link MetricFramework} for Prometheus simpleclient old library.
 */
public class PrometheusSimpleClientFramework extends MetricFramework {

    public final CollectorRegistry registry;

    public PrometheusSimpleClientFramework() {
        registry = new CollectorRegistry(false);

        registerAdapter(MetricType.DOUBLE_COUNTER, createAdapter(Counter::build, Counter.Child::inc));
        registerAdapter(
                MetricType.DOUBLE_GAUGE, createAdapter(Gauge::build, (d, integer) -> d.set(randomLongForGauge())));
    }

    public CollectorRegistry getRegistry() {
        return registry;
    }

    private <D, C extends SimpleCollector<D>, B extends SimpleCollector.Builder<B, C>>
            MetricAdapter<C, D> createAdapter(
                    BiFunction<String, String, B> builderFactory, DataPointUpdater<D> dataPointUpdater) {
        return new MetricAdapter<>(
                (metricId, labelNames) -> builderFactory
                        .apply("metric_" + metricId, UUID.randomUUID().toString())
                        .unit(getUnit(metricId))
                        .labelNames(labelNames)
                        .register(registry),
                SimpleCollector::labels,
                dataPointUpdater);
    }
}
