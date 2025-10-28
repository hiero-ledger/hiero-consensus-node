// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

/**
 * Extension of {@link DataPointSnapshot} for a single value, either {@code long} or {@code double}.
 * * It is sealed to only {@link LongValueDataPointSnapshot} and {@link DoubleValueDataPointSnapshot}.
 */
public sealed interface SingleValueDataPointSnapshot extends DataPointSnapshot
        permits LongValueDataPointSnapshot, DoubleValueDataPointSnapshot {}
