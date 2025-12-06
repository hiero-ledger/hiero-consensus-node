// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

/**
 * A functional interface to fetch a data point given a metric and label values.
 *
 * @param <M> the metric type
 * @param <D> the data point type
 */
@FunctionalInterface
public interface DataPointFetcher<M, D> {

    /**
     * Fetches a data point given a metric and label values.
     *
     * @param metric      the metric
     * @param labelValues the label values
     * @return the fetched data point
     */
    D getDataPoint(M metric, String[] labelValues);
}
