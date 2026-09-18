// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static org.hiero.otter.fixtures.junit.AnnotationUtils.findAnnotation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>Setting {@link FalconTest#failureThreshold()} cuts a sweep short: once that many repetitions have failed, the
 * remaining ones are skipped instead of run.
 */
public class FalconTestExtension implements TestTemplateInvocationContextProvider {

    /**
     * System property that overrides {@link FalconTest#repetitions()}, so that the same test source can run a few
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

        final String testName = context.getRequiredTestMethod().getName();
        final int repetitions = repetitionsFor(testName, falconTest);
        final int failureThreshold = failureThresholdFor(testName, falconTest);
        final AtomicInteger failureCount = new AtomicInteger();
        final Random random = new Random();
        return IntStream.rangeClosed(1, repetitions)
                .mapToObj(index -> FalconInvocationContext.sweep(
                        index, repetitions, random.nextLong(), failureCount, failureThreshold));
    }

    /**
     * Determines the number of repetitions of a sweep, honoring {@link #SYSTEM_PROPERTY_FALCON_REPETITIONS} if set.
     *
     * @param testName the name of the test method, used in error messages
     * @param falconTest the annotation of the test method
     * @return the number of repetitions to run
     */
    private static int repetitionsFor(@NonNull final String testName, @NonNull final FalconTest falconTest) {
        final String override = System.getProperty(SYSTEM_PROPERTY_FALCON_REPETITIONS);
        final int repetitions;
        if (override == null || override.isBlank()) {
            repetitions = falconTest.repetitions();
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
     * Determines the number of failed repetitions that stops a sweep.
     *
     * <p>The threshold is validated against the number of repetitions declared in the annotation rather than against the
     * effective number of repetitions, so that {@link #SYSTEM_PROPERTY_FALCON_REPETITIONS} can lower the repetition
     * count of a build without turning a valid declaration into a configuration error.
     *
     * @param testName the name of the test method, used in error messages
     * @param falconTest the annotation of the test method
     * @return the number of failed repetitions that stops the sweep
     */
    private static int failureThresholdFor(@NonNull final String testName, @NonNull final FalconTest falconTest) {
        final int failureThreshold = falconTest.failureThreshold();
        if (failureThreshold != Integer.MAX_VALUE
                && (failureThreshold < 1 || failureThreshold >= falconTest.repetitions())) {
            throw new IllegalArgumentException(
                    ("Falcon test %s must declare a failure threshold greater than zero and less than its %d "
                                    + "repetitions, but %d was requested")
                            .formatted(testName, falconTest.repetitions(), failureThreshold));
        }
        return failureThreshold;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsTestTemplate(@NonNull final ExtensionContext context) {
        return findAnnotation(context, FalconTest.class).isPresent();
    }
}
