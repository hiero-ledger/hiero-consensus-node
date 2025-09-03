// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.testreader;

/**
 * The result of a JRS test run.
 */
public enum TestStatus {
    /**
     * The test status is could not be determined.
     * This is either old test data without proper marker files,
     * or a test runner failure.
     */
    UNKNOWN,
    /**
     * The test failed.
     */
    FAIL,
    /**
     * The test status.
     */
    PASS
}
