// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static org.hiero.otter.fixtures.junit.AnnotationUtils.findAnnotation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.FalconTest;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

/**
 * A JUnit 5 extension that provides the invocation contexts for tests annotated with {@link FalconTest}.
 *
 * <p>By default a Falcon test runs as a sweep of many repetitions, each with its own randomly drawn seed that is
 * reported in the display name of the repetition. Setting {@link FalconTest#randomSeed()} switches to replaying a
 * single repetition with exactly that seed, which is how a failing repetition of a sweep is reproduced.
 */
public class FalconTestExtension implements TestTemplateInvocationContextProvider {

    /**
     * System property that overrides {@link FalconTest#repetition()}, so that the same test source can run a few
     * repetitions in a pull request build and many in a nightly build.
     */
    public static final String SYSTEM_PROPERTY_FALCON_REPETITIONS = "falcon.repetitions";

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
            @NonNull final ExtensionContext context) {
        final FalconTest falconTest = findAnnotation(context, FalconTest.class).orElseThrow();

        final long pinnedSeed = falconTest.randomSeed();
        if (pinnedSeed != 0L) {
            return Stream.of(FalconInvocationContext.replay(pinnedSeed));
        }

        final int repetitions = repetitionsFor(context.getRequiredTestMethod().getName(), falconTest);
        final Random random = new Random();
        return IntStream.range(0, repetitions)
                .mapToObj(ignored -> FalconInvocationContext.sweep(repetitions, random.nextLong()));
    }

    /**
     * Determines the number of repetitions of a sweep, honoring {@link #SYSTEM_PROPERTY_FALCON_REPETITIONS} if it is
     * set.
     *
     * @param testName the name of the test method, used in error messages
     * @param falconTest the annotation of the test method
     * @return the number of repetitions to run
     */
    private static int repetitionsFor(@NonNull final String testName, @NonNull final FalconTest falconTest) {
        final String override = System.getProperty(SYSTEM_PROPERTY_FALCON_REPETITIONS);
        final int repetitions;
        if (override == null || override.isBlank()) {
            repetitions = falconTest.repetition();
        } else {
            try {
                repetitions = Integer.parseInt(override.trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(
                        "System property '%s' must be a number, but was '%s'"
                                .formatted(SYSTEM_PROPERTY_FALCON_REPETITIONS, override),
                        e);
            }
        }
        if (repetitions < 1) {
            throw new IllegalArgumentException("Falcon test %s must run at least one repetition, but %d were requested"
                    .formatted(testName, repetitions));
        }
        return repetitions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsTestTemplate(@NonNull final ExtensionContext context) {
        return findAnnotation(context, FalconTest.class).isPresent();
    }
}
