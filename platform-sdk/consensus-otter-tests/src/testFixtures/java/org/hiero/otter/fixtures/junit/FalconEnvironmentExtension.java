// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.falcon.FalconTestEnvironment;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * A JUnit 5 extension that provides a {@link TestEnvironment} for tests annotated with {@link FalconTest}.
 */
class FalconEnvironmentExtension implements ParameterResolver, AfterEachCallback {

    private final long randomSeed;

    @Nullable
    private TestEnvironment testEnvironment;

    /**
     * Constructor for {@link FalconEnvironmentExtension}.
     *
     * @param randomSeed the seed for the random number generator used in the test environment
     */
    FalconEnvironmentExtension(final long randomSeed) {
        this.randomSeed = randomSeed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterEach(@NonNull final ExtensionContext context) {
        if (testEnvironment != null) {
            testEnvironment.destroy();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        final Class<?> parameterType = parameterContext.getParameter().getType();
        return (TestEnvironment.class.equals(parameterType));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        final Class<?> parameterType = parameterContext.getParameter().getType();
        if (TestEnvironment.class.equals(parameterType)) {
            if (testEnvironment == null) {
                testEnvironment = new FalconTestEnvironment(randomSeed);
            }
            return testEnvironment;
        }

        throw new ParameterResolutionException("Could not resolve parameter of type: " + parameterType.getName());
    }
}
