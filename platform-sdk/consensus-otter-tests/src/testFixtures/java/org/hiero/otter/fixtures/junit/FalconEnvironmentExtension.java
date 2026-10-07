// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.falcon.FalconTestEnvironment;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * A JUnit 5 extension that provides a {@link TestEnvironment} for tests annotated with {@link FalconTest}.
 */
class FalconEnvironmentExtension implements ParameterResolver, BeforeEachCallback, AfterEachCallback {

    /** Key of the report entry holding the seed of a repetition. */
    private static final String SEED_KEY = "falcon.seed";

    /** Key of the report entry holding the position of a repetition within its sweep. */
    private static final String REPETITION_KEY = "falcon.repetition";

    private final long randomSeed;
    private final String repetition;

    @Nullable
    private TestEnvironment testEnvironment;

    /**
     * Constructor for {@link FalconEnvironmentExtension}.
     *
     * @param randomSeed the seed for the random number generator used in the test environment
     * @param repetition the position of this repetition within its sweep, for example {@code 7/1000}
     */
    FalconEnvironmentExtension(final long randomSeed, @NonNull final String repetition) {
        this.randomSeed = randomSeed;
        this.repetition = requireNonNull(repetition);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeEach(@NonNull final ExtensionContext context) {
        context.publishReportEntry(Map.of(SEED_KEY, Long.toString(randomSeed), REPETITION_KEY, repetition));
        System.out.printf("@%s(randomSeed = %dL)%n", FalconTest.class.getSimpleName(), randomSeed);
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
