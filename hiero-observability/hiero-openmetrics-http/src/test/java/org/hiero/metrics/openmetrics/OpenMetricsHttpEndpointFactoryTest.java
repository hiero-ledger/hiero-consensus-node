// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.util.Optional;
import org.hiero.metrics.api.export.MetricsExporter;
import org.junit.jupiter.api.Test;

public class OpenMetricsHttpEndpointFactoryTest {

    @Test
    public void noExporterCreatedWhenDisabled() {
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.openmetrics.http.enabled", "false")
                .build();
        Optional<MetricsExporter> exporter = new OpenMetricsHttpEndpointFactory().createExporter(config);

        assertThat(exporter).isEmpty();
    }
}
