// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MetricsFileExportConfigTest {

    @Test
    void testDefaultValues() {
        MetricsFileExportConfig config = configBuilder().build().getConfigData(MetricsFileExportConfig.class);

        assertThat(config.enabled()).as("Exporter must be enabled by default.").isTrue();
        assertThat(config.useGzip()).as("useGzip must be true by default.").isTrue();
        assertThat(config.snapshotIntervalSeconds())
                .as("Default interval must be 3 seconds.")
                .isEqualTo(3);
        assertThat(config.bufferSize()).as("Default buffer size must be 8192.").isEqualTo(8192);
        assertThat(config.decimalFormat())
                .as("Default decimal format must be #.###")
                .isEqualTo("#.###");
    }

    @Test
    void testNonDefaultValues() {
        MetricsFileExportConfig config = configBuilder()
                .withValue("metrics.exporter.file.enabled", "false")
                .withValue("metrics.exporter.file.directory", "/var/metrics")
                .withValue("metrics.exporter.file.useGzip", "false")
                .withValue("metrics.exporter.file.snapshotIntervalSeconds", "10")
                .withValue("metrics.exporter.file.bufferSize", "4096")
                .withValue("metrics.exporter.file.decimalFormat", "#.#")
                .build()
                .getConfigData(MetricsFileExportConfig.class);

        assertThat(config.enabled()).isFalse();
        assertThat(config.directory()).isEqualTo(Path.of("/var/metrics"));
        assertThat(config.useGzip()).isFalse();
        assertThat(config.snapshotIntervalSeconds()).isEqualTo(10);
        assertThat(config.bufferSize()).isEqualTo(4096);
        assertThat(config.decimalFormat()).isEqualTo("#.#");
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -10, -1, 2097153, Integer.MAX_VALUE})
    void testNonAllowedBufferSizes(int bufferSize) {
        assertThatThrownBy(() -> configBuilder()
                        .withValue("metrics.exporter.file.bufferSize", String.valueOf(bufferSize))
                        .build())
                .as("Invalid bufferSize " + bufferSize + " must cause a ConfigViolationException.")
                .isInstanceOf(ConfigViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1024, 8192, 2097152})
    void testAllowedBufferSizes(int bufferSize) {
        MetricsFileExportConfig config = configBuilder()
                .withValue("metrics.exporter.file.bufferSize", String.valueOf(bufferSize))
                .build()
                .getConfigData(MetricsFileExportConfig.class);

        assertThat(config.bufferSize())
                .as("Buffer size must be set to " + bufferSize)
                .isEqualTo(bufferSize);
    }

    //TODO test fo positive snapshot intervals

    private ConfigurationBuilder configBuilder() {
        return ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.file.directory", "/tmp");
    }
}
