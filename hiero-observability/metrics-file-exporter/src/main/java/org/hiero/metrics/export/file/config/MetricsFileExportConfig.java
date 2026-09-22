// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.api.validation.annotation.Positive;
import java.nio.file.Path;

/**
 * Configuration for Prometheus Text File Exporter.
 *
 * @param enabled whether exporter is enabled (default: true)
 * @param directory path to directory where file with metrics will be created (required).
 *                  File name will be {@code metrics.txt} or {@code metrics.txt.gz} if {@code useGzip} is true.
 * @param snapshotIntervalSeconds interval in seconds between metric snapshots written to the file (default: 3)
 * @param useGzip whether to gzip the output file (default: true). Metrics compress well due to repeated names and labels.
 * @param bufferSize buffer size in bytes for the output stream (default: 8192, 0 = no buffering)
 * @param decimalFormat the decimal format for numbers (default: #.###)
 */
// spotless:off
@ConfigData("metrics.exporter.file")
public record MetricsFileExportConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty Path directory,
        @ConfigProperty(defaultValue = "3") @Positive int snapshotIntervalSeconds,
        @ConfigProperty(defaultValue = "true") boolean useGzip,
        @ConfigProperty(defaultValue = "8192") @Min(0) @Max(2097152) int bufferSize,
        @ConfigProperty(defaultValue = "#.###") String decimalFormat) {}
// spotless:on
