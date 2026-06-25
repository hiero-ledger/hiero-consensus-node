// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.iss.detection.internal.DefaultIssDetector;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Utility methods for assertions.
 */
public class AssertionUtils {

    private AssertionUtils() {}

    /**
     * Suppresses ISS errors in the log result.
     *
     * @param result the log result to suppress ISS errors from
     * @return the log result with ISS errors suppressed
     */
    @NonNull
    public static SingleNodeLogResult suppressIssErrors(@NonNull final SingleNodeLogResult result) {
        return result.suppressingLoggerName(DefaultIssDetector.class);
    }

    /**
     * Suppresses ISS errors in the log results.
     *
     * @param results the log results to suppress ISS errors from
     * @return the log results with ISS errors suppressed
     */
    @NonNull
    public static MultipleNodeLogResults suppressIssErrors(@NonNull final MultipleNodeLogResults results) {
        return results.suppressingLoggerName(DefaultIssDetector.class);
    }
}
