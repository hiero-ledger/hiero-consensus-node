// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.TestEnvironment;

/**
 * Wrapper class to manage the lifecycle of a {@link TestEnvironment} in the JUnit extension context.
 */
public final class Lifecycle {
    private final String testId;
    private final TestEnvironment environment;

    /**
     * Creates a new lifecycle wrapper.
     *
     * @param testId the unique test identifier
     * @param environment the test environment instance
     */
    public Lifecycle(@NonNull final String testId, @NonNull final TestEnvironment environment) {
        this.testId = requireNonNull(testId, "testId must not be null");
        this.environment = requireNonNull(environment, "environment must not be null");
    }

    /**
     * Gets the test environment.
     *
     * @return the test environment
     */
    @NonNull
    public TestEnvironment environment() {
        return environment;
    }

    /**
     * Gets the unique test identifier.
     *
     * @return the test ID
     */
    @NonNull
    public String testId() {
        return testId;
    }
}
