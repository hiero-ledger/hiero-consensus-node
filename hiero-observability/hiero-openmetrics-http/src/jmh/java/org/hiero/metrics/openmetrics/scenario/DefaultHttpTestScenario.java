// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.scenario;

import java.io.IOException;
import org.hiero.metrics.api.core.MetricsFacade;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.openmetrics.OpenMetricsHttpEndpoint;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpEndpointConfig;
import org.hiero.metrics.test.fixtures.framework.DefaultMetricsFramework;

/**
 * HTTP test scenario using {@link DefaultMetricsFramework}.
 */
public class DefaultHttpTestScenario extends AbstractHttpTestScenario<DefaultMetricsFramework> {

    private final MetricsExportManager exportManager;

    public DefaultHttpTestScenario() throws IOException {
        super(new DefaultMetricsFramework());

        exportManager = MetricsFacade.createExportManager(
                new OpenMetricsHttpEndpoint(new OpenMetricsHttpEndpointConfig(true, getPort(), getPath(), 0)));
        exportManager.manageMetricRegistry(getFramework().getMetricRegistry());
    }

    @Override
    public void close() {
        super.close();
        exportManager.shutdown();
    }
}
