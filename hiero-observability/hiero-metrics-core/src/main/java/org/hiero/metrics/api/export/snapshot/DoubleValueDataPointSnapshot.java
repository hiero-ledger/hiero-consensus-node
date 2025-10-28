// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import java.util.function.DoubleSupplier;

/**
 * Extension of {@link DataPointSnapshot} for a single {@code double} value.
 */
public non-sealed interface DoubleValueDataPointSnapshot extends SingleValueDataPointSnapshot, DoubleSupplier {}
