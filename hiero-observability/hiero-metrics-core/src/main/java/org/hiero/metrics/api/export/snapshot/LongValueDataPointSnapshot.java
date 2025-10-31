// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import java.util.function.LongSupplier;

/**
 * Extension of {@link DataPointSnapshot} for a single {@code long} value.
 */
public non-sealed interface LongValueDataPointSnapshot extends SingleValueDataPointSnapshot, LongSupplier {}
