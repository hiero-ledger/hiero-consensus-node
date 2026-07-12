// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.nio.file.Path;
import java.util.List;
import org.hiero.metrics.core.MetricsExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsFileExporterFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void testNoExporterCreatedWhenDisabled() {
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.file.enabled", "false")
                .withValue("metrics.exporter.file.directory", tempDir.toString())
                .build();

        MetricsExporter exporter = new MetricsFileExporterFactory().createExporter(List.of(), config);

        assertThat(exporter).isNull();
    }

    @Test
    void testExporterCreatedWhenEnabled() throws Exception {
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.file.directory", tempDir.toString())
                .build();

        MetricsExporter exporter = new MetricsFileExporterFactory().createExporter(List.of(), config);

        assertThat(exporter).isNotNull().isInstanceOf(MetricsFileExporter.class);
        exporter.close();
    }
}
