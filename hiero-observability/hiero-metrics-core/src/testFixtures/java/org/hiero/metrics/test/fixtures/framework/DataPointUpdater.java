// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

/**
 * A functional interface to update a data point given its id.
 *
 * @param <D> the data point type
 */
@FunctionalInterface
public interface DataPointUpdater<D> {

    /**
     * Updates a data point given its id.
     *
     * @param dataPoint   the data point
     * @param dataPointId the data point id
     */
    void updateDataPoint(D dataPoint, int dataPointId);
}
