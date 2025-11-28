// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

/**
 * A functional interface to create a metric given its id and label names.
 *
 * @param <M> the metric type
 */
@FunctionalInterface
public interface MetricFactory<M> {

    /**
     * Creates a metric given its id and label names.
     *
     * @param metricId   the metric id
     * @param labelNames the label names
     * @return the created metric
     */
    M create(int metricId, String[] labelNames);
}
