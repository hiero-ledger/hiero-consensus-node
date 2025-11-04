// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.scenario;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import java.io.IOException;
import org.hiero.metrics.openmetrics.frameworks.PrometheusFramework;

/**
 * HTTP test scenario using {@link PrometheusFramework}.
 */
public class PrometheusTestScenario extends AbstractHttpTestScenario<PrometheusFramework> {

    private final HTTPServer httpServer;

    public PrometheusTestScenario() throws IOException {
        super(new PrometheusFramework());

        httpServer = io.prometheus.metrics.exporter.httpserver.HTTPServer.builder()
                .port(getPort())
                .registry(getFramework().getRegistry())
                .buildAndStart();
    }

    @Override
    public void close() {
        super.close();
        httpServer.close();
    }
}
