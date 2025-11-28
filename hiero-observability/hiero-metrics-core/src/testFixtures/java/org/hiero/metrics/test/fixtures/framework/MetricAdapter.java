// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

/**
 * A record that adapts metric creation, data point fetching, and data point updating.
 *
 * @param <M> the metric type
 * @param <D> the data point type
 */
public record MetricAdapter<M, D>(
        MetricFactory<M> metricFactory, DataPointFetcher<M, D> dataPointGetter, DataPointUpdater<D> dataPointUpdater) {

    public M createMetric(int metricId, String[] labelNames) {
        return metricFactory.create(metricId, labelNames);
    }

    public void updateDataPoint(M metric, String[] labelValues, int dataPointId) {
        dataPointUpdater.updateDataPoint(getDataPoint(metric, labelValues), dataPointId);
    }

    public D getDataPoint(M metric, String[] labelValues) {
        return dataPointGetter.getDataPoint(metric, labelValues);
    }
}
