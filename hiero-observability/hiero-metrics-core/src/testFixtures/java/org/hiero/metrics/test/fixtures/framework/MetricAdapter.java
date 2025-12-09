// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

/**
 * A record that adapts metric creation, measurement fetching, and measurement updating.
 *
 * @param <M> the metric type
 * @param <D> the measurement type
 */
public record MetricAdapter<M, D>(
        MetricFactory<M> metricFactory,
        MeasurementFetcher<M, D> measurementGetter,
        MeasurementUpdater<D> measurementUpdater) {

    public M createMetric(int metricId, String[] labelNames) {
        return metricFactory.create(metricId, labelNames);
    }

    public void updateMeasurement(M metric, String[] labelValues, int measurementId) {
        measurementUpdater.updateMeasurement(getMeasurement(metric, labelValues), measurementId);
    }

    public D getMeasurement(M metric, String[] labelValues) {
        return measurementGetter.getMeasurement(metric, labelValues);
    }
}
