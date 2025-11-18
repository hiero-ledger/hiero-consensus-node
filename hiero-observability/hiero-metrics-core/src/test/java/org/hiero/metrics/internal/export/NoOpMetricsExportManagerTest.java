// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.junit.jupiter.api.Test;

public class NoOpMetricsExportManagerTest {

    @Test
    void testNoOpExportManager() {
        MetricRegistry registry = MetricRegistry.builder("test-registry").build();
        MetricsExportManager manager = new NoOpMetricsExportManager(registry);

        assertThat(manager.registry()).isSameAs(registry);
        assertThat(manager.hasRunningExportThread()).isFalse();

        // Calling shutdown should not throw any exceptions
        manager.shutdown();
    }
}
