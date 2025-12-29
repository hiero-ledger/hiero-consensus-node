// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import org.hiero.metrics.api.core.ArrayAccessor;

/**
 * Snapshot of metrics at some point in time.<br>
 * Extends {@link Iterable} over {@link MetricSnapshot}.
 */
public interface MetricsCollectionSnapshot extends ArrayAccessor<MetricSnapshot> {}
