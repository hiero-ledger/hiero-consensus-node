// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

/**
 * Extension of {@link MeasurementSnapshot} for a single value, either {@code long} or {@code double}.
 * It is sealed to only {@link LongValueMeasurementSnapshot} and {@link DoubleValueMeasurementSnapshot}.
 */
public sealed interface SingleValueMeasurementSnapshot extends MeasurementSnapshot
        permits LongValueMeasurementSnapshot, DoubleValueMeasurementSnapshot {}
