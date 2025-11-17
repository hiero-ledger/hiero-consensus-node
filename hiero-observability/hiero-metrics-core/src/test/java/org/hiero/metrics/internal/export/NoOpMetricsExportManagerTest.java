// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.junit.jupiter.api.Test;

public class NoOpMetricsExportManagerTest {

    @Test
    void testNoOpExportManager() {
        MetricsExportManager manager = NoOpMetricsExportManager.INSTANCE;

        assertThat(manager.name()).isEqualTo("no-op");
        assertThat(manager.hasRunningExportThread()).isFalse();
        assertThat(manager.manageMetricRegistry(MetricRegistry.builder().build()))
                .isFalse();

        // Calling resetAll and shutdown should not throw any exceptions
        manager.resetAll();
        manager.shutdown();
    }
}
