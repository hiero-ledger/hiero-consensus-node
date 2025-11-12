// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.frameworks;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.MetricWithFixedMetadata;
import io.prometheus.metrics.core.metrics.StateSet;
import io.prometheus.metrics.core.metrics.StatefulMetric;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.Unit;
import java.util.UUID;
import java.util.function.Supplier;
import org.hiero.metrics.test.fixtures.SateSetEnum;
import org.hiero.metrics.test.fixtures.framework.DataPointUpdater;
import org.hiero.metrics.test.fixtures.framework.MetricAdapter;
import org.hiero.metrics.test.fixtures.framework.MetricFramework;
import org.hiero.metrics.test.fixtures.framework.MetricType;

/**
 * {@link MetricFramework} for Prometheus new library.
 */
public class PrometheusFramework extends MetricFramework {

    private final PrometheusRegistry registry;

    public PrometheusFramework() {
        registry = new PrometheusRegistry();

        registerAdapter(MetricType.DOUBLE_COUNTER, createAdapter(Counter::builder, CounterDataPoint::inc));
        registerAdapter(
                MetricType.DOUBLE_GAUGE, createAdapter(Gauge::builder, (d, integer) -> d.set(randomLongForGauge())));
        registerAdapter(
                MetricType.STATE_SET,
                createAdapter(
                        () -> StateSet.builder().states(SateSetEnum.class),
                        (d, dataPointId) ->
                                d.setFalse(SateSetEnum.randomStateSet().name())));
    }

    public PrometheusRegistry getRegistry() {
        return registry;
    }

    private <D extends DataPoint, M extends StatefulMetric<D, ?>, B extends MetricWithFixedMetadata.Builder<B, M>>
            MetricAdapter<M, D> createAdapter(Supplier<B> builderFactory, DataPointUpdater<D> dataPointUpdater) {
        return new MetricAdapter<>(
                (metricId, labelNames) -> builderFactory
                        .get()
                        .name("metric_" + metricId)
                        .help(UUID.randomUUID().toString())
                        .unit(getUnitObject(metricId))
                        .labelNames(labelNames)
                        .register(registry),
                io.prometheus.metrics.core.metrics.StatefulMetric::labelValues,
                dataPointUpdater);
    }

    private Unit getUnitObject(int metricId) {
        String unit = getUnit(metricId);
        return switch (unit) {
            case "seconds" -> Unit.SECONDS;
            case "bytes" -> Unit.BYTES;
            default -> null;
        };
    }
}
