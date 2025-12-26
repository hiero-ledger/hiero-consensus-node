// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import java.util.function.DoubleSupplier;

/**
 * Extension of {@link MeasurementSnapshot} for a single {@code double} value.
 */
public interface DoubleValueMeasurementSnapshot extends MeasurementSnapshot, DoubleSupplier {}
