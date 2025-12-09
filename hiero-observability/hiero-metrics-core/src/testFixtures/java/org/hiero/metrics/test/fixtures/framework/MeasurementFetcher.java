// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

/**
 * A functional interface to fetch a measurement given a metric and label values.
 *
 * @param <M> the metric type
 * @param <D> the measurement type
 */
@FunctionalInterface
public interface MeasurementFetcher<M, D> {

    /**
     * Fetches a measurement given a metric and label values.
     *
     * @param metric      the metric
     * @param labelValues the label values
     * @return the fetched measurement
     */
    D getMeasurement(M metric, String[] labelValues);
}
