// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testing MetricsConfig")
class MetricsConfigTest {

    static final String DEFAULT_METRICS_UPDATE_PERIOD_MILLIS = "1000";
    static final String DEFAULT_DISABLE_METRICS_OUTPUT = "false";
    static final String DEFAULT_CSV_OUTPUT_FOLDER = "data/stats";
    static final String DEFAULT_CSV_FILE_NAME = "MainNetStats";
    static final String DEFAULT_CSV_APPEND = "false";
    static final String DEFAULT_CSV_WRITE_FREQUENCY = "3000";
    static final String DEFAULT_METRICS_DOC_FILE_NAME = "metricsDoc.tsv";

    @Test
    @DisplayName("Testing default metrics configuration")
    void testDefaultMetricsConfig() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);

        assertThat(metricsConfig).isNotNull();
        assertThat(metricsConfig.metricsUpdatePeriodMillis())
                .isEqualTo(Long.valueOf(DEFAULT_METRICS_UPDATE_PERIOD_MILLIS));
        assertThat(metricsConfig.disableMetricsOutput()).isEqualTo(Boolean.valueOf(DEFAULT_DISABLE_METRICS_OUTPUT));
        assertThat(metricsConfig.csvOutputFolder()).isEqualTo(DEFAULT_CSV_OUTPUT_FOLDER);
        assertThat(metricsConfig.csvFileName()).isEqualTo(DEFAULT_CSV_FILE_NAME);
        assertThat(metricsConfig.csvAppend()).isEqualTo(Boolean.valueOf(DEFAULT_CSV_APPEND));
        assertThat(metricsConfig.csvWriteFrequency()).isEqualTo(Integer.valueOf(DEFAULT_CSV_WRITE_FREQUENCY));
        assertThat(metricsConfig.metricsDocFileName()).isEqualTo(DEFAULT_METRICS_DOC_FILE_NAME);
    }

    @Test
    @DisplayName("Testing custom metrics configuration")
    void testCustomMetricsConfig() throws Exception {
        final Path configFile = Paths.get(
                Objects.requireNonNull(MetricsConfigTest.class.getClassLoader().getResource("metrics-test.properties"))
                        .toURI());
        final Configuration configuration = new TestConfigBuilder()
                .withSource(new PropertyFileConfigSource(configFile))
                .getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);

        assertThat(metricsConfig).isNotNull();
        assertThat(metricsConfig.metricsUpdatePeriodMillis()).isEqualTo(2000);
        assertThat(metricsConfig.disableMetricsOutput()).isTrue();
        assertThat(metricsConfig.csvOutputFolder()).isEqualTo("./metrics-output");
        assertThat(metricsConfig.csvFileName()).isEqualTo("metrics-test");
        assertThat(metricsConfig.csvAppend()).isTrue();
        assertThat(metricsConfig.csvWriteFrequency()).isEqualTo(6000);
        assertThat(metricsConfig.metricsDocFileName()).isEqualTo("metricsDoc-test.tsv");
    }

    @Test
    @DisplayName("Testing getMetricsUpdateDuration")
    void getMetricsUpdateDuration() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);

        assertThat(metricsConfig.getMetricsUpdateDuration())
                .isEqualTo(Duration.ofMillis(metricsConfig.metricsUpdatePeriodMillis()));
    }

    @Test
    @DisplayName("Testing getMetricsSnapshotDuration")
    void getMetricsSnapshotDuration() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);

        assertThat(metricsConfig.getMetricsSnapshotDuration())
                .isEqualTo(Duration.ofMillis(metricsConfig.csvWriteFrequency()));
    }
}
