// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.scenario;

import io.prometheus.client.exporter.HTTPServer;
import java.io.IOException;
import org.hiero.metrics.openmetrics.frameworks.PrometheusSimpleClientFramework;

/**
 * HTTP test scenario using {@link PrometheusSimpleClientFramework}.
 */
public class PrometheusSimpleClientTestScenario extends AbstractHttpTestScenario<PrometheusSimpleClientFramework> {

    private final HTTPServer httpServer;

    public PrometheusSimpleClientTestScenario() throws IOException {
        super(new PrometheusSimpleClientFramework());

        httpServer = new io.prometheus.client.exporter.HTTPServer.Builder()
                .withPort(getPort())
                .withRegistry(getFramework().getRegistry())
                .build();
    }

    @Override
    public void close() {
        super.close();
        httpServer.close();
    }
}
