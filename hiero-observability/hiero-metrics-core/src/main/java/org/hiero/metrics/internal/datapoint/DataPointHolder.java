// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;

/**
 * Holder for a data point and its snapshot.
 *
 * @param <D> the type of the data point
 * @param <S> the type of the data point snapshot
 * @param dataPoint the data point
 * @param snapshot the snapshot of the data point
 */
public record DataPointHolder<D, S extends DataPointSnapshot>(D dataPoint, S snapshot) {}
