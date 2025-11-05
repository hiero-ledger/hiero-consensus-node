// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.TestEnvironment;

/**
 * Wrapper record to manage the lifecycle of a {@link TestEnvironment} in the JUnit extension context.
 *
 * @param testId      the unique identifier for the test
 * @param environment the test environment to manage
 */
public record Lifecycle (@NonNull String testId, @NonNull TestEnvironment environment) {
    /**
     * Creates a new Lifecycle instance.
     *
     * @param testId      the unique identifier for the test
     * @param environment the test environment to manage
     * @throws NullPointerException if any of the parameters are null
     */
    public Lifecycle {
        requireNonNull(testId, "testId must not be null");
        requireNonNull(environment, "environment must not be null");
    }
}
