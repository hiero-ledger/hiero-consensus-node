// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for Metrics
 *
 * @param metricsUpdatePeriodMillis
 *         the metrics update period in milliseconds
 * @param disableMetricsOutput
 *         Disable all metrics-outputs. If {@code true}, this overrides all other specific settings
 *         concerning metrics-output.
 * @param csvOutputFolder
 *         The absolute or relative folder path where all the statistics CSV files will be written.
 *         If this value is null or an empty string, the current folder selection behavior will be
 *         used (ie: the SDK base path).
 * @param csvFileName
 *         The prefix of the name of the CSV file that the platform will write statistics to.
 *         If this value is null or an empty string, the platform will not write any statistics.
 * @param csvAppend
 *         Indicates whether statistics should be appended to the CSV file.
 * @param csvWriteFrequency
 *         The frequency, in milliseconds, at which values are written to the statistics CSV file.
 * @param metricsDocFileName
 *         the file name to be used for Metrics document generation
 * @param halfLife
 * 		   half life of some of the various statistics (give half the weight to the last halfLife seconds)
 */
@ConfigData("metrics")
public record MetricsConfig(
        @Min(0) @ConfigProperty(defaultValue = "1000") long metricsUpdatePeriodMillis,
        @ConfigProperty(defaultValue = "false") boolean disableMetricsOutput,
        @ConfigProperty(defaultValue = "data/stats") String csvOutputFolder,
        @ConfigProperty(defaultValue = "MainNetStats") String csvFileName,
        @ConfigProperty(defaultValue = "false") boolean csvAppend,
        @Min(0) @ConfigProperty(defaultValue = "3000") int csvWriteFrequency,
        @ConfigProperty(defaultValue = "metricsDoc.tsv") String metricsDocFileName,
        @ConfigProperty(defaultValue = "10") double halfLife) {

    /**
     * Returns the metrics update interval time as a {@link Duration}.
     *
     * @return the metrics update duration
     */
    public Duration getMetricsUpdateDuration() {
        return Duration.ofMillis(metricsUpdatePeriodMillis);
    }

    /**
     * Returns the metrics snapshot interval time as a {@link Duration}.
     *
     * @return the metrics snapshot duration
     */
    public Duration getMetricsSnapshotDuration() {
        return Duration.ofMillis(csvWriteFrequency);
    }
}
