// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;

/**
 * Holder for a measurement and its snapshot.
 *
 * @param <M> the type of the measurement
 * @param measurement the measurement
 * @param snapshot the snapshot of the measurement
 */
public record MeasurementAndSnapshot<M>(M measurement, MeasurementSnapshot snapshot) {}
