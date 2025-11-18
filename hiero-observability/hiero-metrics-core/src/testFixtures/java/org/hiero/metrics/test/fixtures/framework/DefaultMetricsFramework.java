// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

import java.util.UUID;
import java.util.function.Function;
import org.hiero.metrics.api.BooleanGauge;
import org.hiero.metrics.api.DoubleCounter;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.StateSet;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.test.fixtures.SateSetEnum;

/**
 * A default implementation of {@link MetricFramework} for testing purposes of current framework.
 * Counters have increment updaters, gauges have random value updaters in a range.
 */
public final class DefaultMetricsFramework extends MetricFramework {

    private final MetricRegistry metricRegistry =
            MetricRegistry.builder("test_registry").build();

    public DefaultMetricsFramework() {
        registerAdapter(
                MetricType.LONG_COUNTER,
                createAdapter(LongCounter::builder, (dataPoint, dataPointId) -> dataPoint.increment()));

        registerAdapter(
                MetricType.LONG_GAUGE,
                createAdapter(LongGauge::builder, (dataPoint, dataPointId) -> dataPoint.update(randomLongForGauge())));

        registerAdapter(
                MetricType.DOUBLE_COUNTER,
                createAdapter(DoubleCounter::builder, (dataPoint, dataPointId) -> dataPoint.increment()));

        registerAdapter(
                MetricType.DOUBLE_GAUGE,
                createAdapter(
                        DoubleGauge::builder, (dataPoint, dataPointId) -> dataPoint.update(randomDoubleForGauge())));

        registerAdapter(
                MetricType.BOOLEAN_GAUGE,
                createAdapter(BooleanGauge::builder, (dataPoint, dataPointId) -> dataPoint.set(randomBoolean())));

        registerAdapter(
                MetricType.STATE_SET,
                createAdapter(
                        metricId -> StateSet.builder("metric_" + metricId, SateSetEnum.class),
                        (dataPoint, dataPointId) -> dataPoint.set(SateSetEnum.randomStateSet(), randomBoolean())));
    }

    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    @Override
    protected String[] initLabelValuesTemplate(String[] labelNames) {
        if (labelNames.length == 0) {
            return EMPTY_LABELS;
        }
        String[] namesAndValues = new String[labelNames.length * 2];
        for (int i = 0; i < labelNames.length; i++) {
            namesAndValues[i * 2] = labelNames[i];
        }
        return namesAndValues;
    }

    @Override
    protected void updateLabelValue(String[] namesAndValues, int labelIdx, String value) {
        namesAndValues[2 * labelIdx + 1] = value;
    }

    private <D, M extends StatefulMetric<?, D>, B extends StatefulMetric.Builder<?, D, B, M>>
            MetricAdapter<M, D> createAdapter(
                    Function<String, B> builderFactory, DataPointUpdater<D> dataPointUpdater) {
        return new MetricAdapter<>(
                (metricId, labelNames) -> basicSetup(builderFactory.apply("metric_" + metricId), metricId)
                        .withDynamicLabelNames(labelNames)
                        .register(metricRegistry),
                StatefulMetric::getOrCreateLabeled,
                dataPointUpdater);
    }

    private <B extends Metric.Builder<B, ?>> B basicSetup(B builder, int metricId) {
        return builder.withDescription(UUID.randomUUID().toString()).withUnit(getUnit(metricId));
    }
}
