// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;

/**
 * Holder for a measurement and its snapshot.
 *
 * @param <D> the type of the measurement
 * @param <S> the type of the measurement snapshot
 * @param measurement the measurement
 * @param snapshot the snapshot of the measurement
 */
public record MeasurementHolder<D, S extends MeasurementSnapshot>(D measurement, S snapshot) {}
