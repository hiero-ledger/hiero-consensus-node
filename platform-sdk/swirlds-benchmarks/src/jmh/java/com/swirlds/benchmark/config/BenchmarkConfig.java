// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.config;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Settings for the benchmarks
 *
 * @param benchmarkData
 * 		parameter had no description in the old settings
 * @param saveDataDirectory
 * 		parameter had no description in the old settings
 * @param verifyResult
 * 		parameter had no description in the old settings
 * @param enableSnapshots
 *      Indicates whether to take snapshots periodically during benchmarks
 * @param printHistogram
 * 		parameter had no description in the old settings
 * @param csvOutputFolder
 * 		The absolute or relative folder path where all the statistics CSV files will be written. If this value is null
 * 		or an empty string, the current folder selection behavior will be used (ie: the SDK base
 * 		path).
 * @param csvMetricsFileName
 * 		CSV file name that the platform will write statistics to. If this value is null or an
 * 		empty string, the platform will not write any statistics.
 * @param csvMetricNamesFileName
 * 		CSV file name that the platform will write all metric names to.
 * @param csvWriteFrequency
 * 		The frequency, in milliseconds, at which values are written to the statistics CSV file.
 * @param csvAppend
 * 		Indicates whether statistics should be appended to the CSV file.
 */
@ConfigData("benchmark")
public record BenchmarkConfig(
        @ConfigProperty(defaultValue = "") String benchmarkData,
        @ConfigProperty(defaultValue = "false") boolean saveDataDirectory,
        @ConfigProperty(defaultValue = "true") boolean verifyResult,
        @ConfigProperty(defaultValue = "false") boolean enableSnapshots,
        @ConfigProperty(defaultValue = "false") boolean printHistogram,
        @ConfigProperty(defaultValue = "") String csvOutputFolder,
        @ConfigProperty(defaultValue = "BenchmarkMetrics.csv") String csvMetricsFileName,
        @ConfigProperty(defaultValue = "BenchmarkMetricNames.csv") String csvMetricNamesFileName,
        @ConfigProperty(defaultValue = "0") int csvWriteFrequency,
        @ConfigProperty(defaultValue = "false") boolean csvAppend,
        @ConfigProperty(defaultValue = "sda") String deviceName) {
    public String toString() {
        return new ToStringBuilder(this)
                .append("benchmarkData", benchmarkData)
                .append("saveDataDirectory", saveDataDirectory)
                .append("verifyResult", verifyResult)
                .append("enableSnapshots", enableSnapshots)
                .append("printHistogram", printHistogram)
                .append("csvOutputFolder", csvOutputFolder)
                .append("csvMetricsFileName", csvMetricsFileName)
                .append("csvMetricNamesFileName", csvMetricNamesFileName)
                .append("csvWriteFrequency", csvWriteFrequency)
                .append("csvAppend", csvAppend)
                .append("deviceName", deviceName)
                .toString();
    }
}
