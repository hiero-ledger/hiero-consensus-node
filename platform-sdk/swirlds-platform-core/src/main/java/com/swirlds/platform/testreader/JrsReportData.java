// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.testreader;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;

/**
 * Contains the data for a JRS test report.
 *
 * @param directory   the root of the GCP directory that was read to gather this data
 * @param reportTime  the time when the data was gathered
 * @param reportSpan  the number of days covered by the report
 * @param testResults individual test results
 */
public record JrsReportData(
        @NonNull String directory,
        @NonNull Instant reportTime,
        int reportSpan,
        @NonNull List<JrsTestResult> testResults) {}
