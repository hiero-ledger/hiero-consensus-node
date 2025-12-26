// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import java.util.function.LongSupplier;

/**
 * Extension of {@link MeasurementSnapshot} for a single {@code long} value.
 */
public interface LongValueMeasurementSnapshot extends MeasurementSnapshot, LongSupplier {}
