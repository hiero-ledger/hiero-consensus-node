// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

/**
 * A functional interface to update a measurement given its id.
 *
 * @param <D> the measurement type
 */
@FunctionalInterface
public interface MeasurementUpdater<D> {

    /**
     * Updates a measurement given its id.
     *
     * @param measurement   the measurement
     * @param measurementId the measurement id
     */
    void updateMeasurement(D measurement, int measurementId);
}
