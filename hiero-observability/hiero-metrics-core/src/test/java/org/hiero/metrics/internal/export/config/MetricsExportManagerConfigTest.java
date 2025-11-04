// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.ConfigurationBuilder;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class MetricsExportManagerConfigTest {

    private static final String TEST_EXPORTER = "testExporter";

    @Test
    public void defaultValues() {
        MetricsExportManagerConfig endpointConfig =
                configBuilder().build().getConfigData(MetricsExportManagerConfig.class);

        assertThat(endpointConfig.enabled())
                .as("Export manager must be enabled by default.")
                .isTrue();

        assertThat(endpointConfig.disabledExporters())
                .as("Export manager disabled exporters must sbe null by default.")
                .isNull();

        assertThat(endpointConfig.enabledExporters())
                .as("Export manager enabled exporters must sbe null by default.")
                .isNull();
    }

    @Test
    public void emptyExporters() {
        MetricsExportManagerConfig endpointConfig = configBuilder()
                .withValue("metrics.export.manager.disabledExporters", "")
                .withValue("metrics.export.manager.enabledExporters", "")
                .build()
                .getConfigData(MetricsExportManagerConfig.class);

        assertThat(endpointConfig.disabledExporters())
                .as("Export manager disabled exporters must be empty.")
                .isEmpty();

        assertThat(endpointConfig.enabledExporters())
                .as("Export manager enabled exporters must be empty.")
                .isEmpty();
    }

    @Test
    public void nonDefaultValues() {
        MetricsExportManagerConfig endpointConfig = configBuilder()
                .withValue("metrics.export.manager.enabled", "false")
                .withValue("metrics.export.manager.disabledExporters", "exp1,exp2")
                .withValue("metrics.export.manager.enabledExporters", "exp3,exp4")
                .build()
                .getConfigData(MetricsExportManagerConfig.class);

        assertThat(endpointConfig.enabled())
                .as("Export manager must be disabled.")
                .isFalse();

        assertThat(endpointConfig.disabledExporters())
                .as("Export manager disabled exporters must contain 2 items.")
                .containsExactlyInAnyOrder("exp1", "exp2");

        assertThat(endpointConfig.enabledExporters())
                .as("Export manager enabled exporters must contain 2 items.")
                .containsExactlyInAnyOrder("exp3", "exp4");
    }

    @ParameterizedTest
    @MethodSource("enabledExporterSource")
    public void enabledExporter(String enabled, String enabledExporters, String disabledExporters) {
        ConfigurationBuilder configBuilder = configBuilder().withValue("metrics.export.manager.enabled", enabled);

        if (enabledExporters != null) {
            configBuilder = configBuilder.withValue("metrics.export.manager.enabledExporters", enabledExporters);
        }
        if (disabledExporters != null) {
            configBuilder = configBuilder.withValue("metrics.export.manager.disabledExporters", disabledExporters);
        }

        MetricsExportManagerConfig endpointConfig =
                configBuilder.build().getConfigData(MetricsExportManagerConfig.class);

        assertThat(endpointConfig.isExporterEnabled(TEST_EXPORTER))
                .as("Exporter " + TEST_EXPORTER + " should be enabled.")
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("disabledExporterSource")
    public void disabledExporter(String enabled, String enabledExporters, String disabledExporters) {
        ConfigurationBuilder configBuilder = configBuilder().withValue("metrics.export.manager.enabled", enabled);

        if (enabledExporters != null) {
            configBuilder = configBuilder.withValue("metrics.export.manager.enabledExporters", enabledExporters);
        }
        if (disabledExporters != null) {
            configBuilder = configBuilder.withValue("metrics.export.manager.disabledExporters", disabledExporters);
        }

        MetricsExportManagerConfig endpointConfig =
                configBuilder.build().getConfigData(MetricsExportManagerConfig.class);

        assertThat(endpointConfig.isExporterEnabled(TEST_EXPORTER))
                .as("Exporter " + TEST_EXPORTER + " should be disabled.")
                .isFalse();
    }

    private static Stream<Arguments> enabledExporterSource() {
        return Stream.of(
                Arguments.of("true", null, null),
                Arguments.of("true", "", null),
                Arguments.of("true", null, ""),
                Arguments.of("true", null, TEST_EXPORTER + "1"),
                Arguments.of("true", "", TEST_EXPORTER + "1"),
                Arguments.of("true", TEST_EXPORTER, null),
                Arguments.of("true", TEST_EXPORTER, ""),
                Arguments.of("true", TEST_EXPORTER, TEST_EXPORTER + "1"),
                Arguments.of("true", "otherEnabled," + TEST_EXPORTER, null),
                Arguments.of("true", "otherEnabled," + TEST_EXPORTER, TEST_EXPORTER + "1"));
    }

    private static Stream<Arguments> disabledExporterSource() {
        return Stream.of(
                Arguments.of("false", null, null),
                Arguments.of("false", "", null),
                Arguments.of("false", null, ""),
                Arguments.of("false", TEST_EXPORTER, null),
                Arguments.of("false", TEST_EXPORTER, ""),
                Arguments.of("false", "otherEnabled," + TEST_EXPORTER, null),
                Arguments.of("true", null, TEST_EXPORTER),
                Arguments.of("true", null, "otherDisabled," + TEST_EXPORTER),
                Arguments.of("true", "", TEST_EXPORTER),
                Arguments.of("true", TEST_EXPORTER + "1", TEST_EXPORTER),
                Arguments.of("true", TEST_EXPORTER, TEST_EXPORTER));
    }

    private ConfigurationBuilder configBuilder() {
        return ConfigurationBuilder.create().autoDiscoverExtensions();
    }
}
