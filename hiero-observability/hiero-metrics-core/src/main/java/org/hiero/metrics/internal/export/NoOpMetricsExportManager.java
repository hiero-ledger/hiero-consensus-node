// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;

/**
 * A no-op implementation of the {@link MetricsExportManager} interface.
 */
public final class NoOpMetricsExportManager implements MetricsExportManager {

    private final MetricRegistry metricRegistry;

    public NoOpMetricsExportManager(MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    @NonNull
    @Override
    public MetricRegistry registry() {
        return metricRegistry;
    }

    @Override
    public boolean hasRunningExportThread() {
        return false;
    }

    @Override
    public void shutdown() {
        // no op
    }
}
