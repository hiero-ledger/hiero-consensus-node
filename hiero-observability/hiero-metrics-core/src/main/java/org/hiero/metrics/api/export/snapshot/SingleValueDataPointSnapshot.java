// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import java.util.function.DoubleSupplier;

/**
 * A {@link DataPointSnapshot} that contains a single {@code double} value, accessible via {@link #getAsDouble()}.
 */
public interface SingleValueDataPointSnapshot extends DataPointSnapshot, DoubleSupplier {}
