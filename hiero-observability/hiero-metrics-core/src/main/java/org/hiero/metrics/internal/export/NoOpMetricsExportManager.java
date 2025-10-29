// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;

/**
 * A no-op implementation of the {@link MetricsExportManager} interface.
 */
public final class NoOpMetricsExportManager implements MetricsExportManager {

    public static final MetricsExportManager INSTANCE = new NoOpMetricsExportManager();

    @NonNull
    @Override
    public String name() {
        return "no-op";
    }

    @Override
    public boolean manageMetricRegistry(@NonNull MetricRegistry metricRegistry) {
        return false;
    }

    @Override
    public void resetAll() {
        // no op
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
