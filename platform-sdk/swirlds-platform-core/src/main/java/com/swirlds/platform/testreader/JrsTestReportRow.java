// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.testreader;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A row in a JRS Test Report.
 *
 * @param tests    the results of the tests in the row, ordered from most to least recent
 * @param metadata test metadata that is not parsed from results
 */
public record JrsTestReportRow(@NonNull List<JrsTestResult> tests, @Nullable JrsTestMetadata metadata) {

    /**
     * Get the most recent test in this row.
     */
    @NonNull
    public JrsTestResult getMostRecentTest() {
        return tests.get(0);
    }
}
