// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.hiero.otter.fixtures.FalconTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;

/**
 * Tests the {@link FalconTest#failureThreshold()} support of {@link FalconTestExtension}.
 */
@DisplayName("Falcon failure threshold Test")
class FalconFailureThresholdTest {

    private final FalconTestExtension extension = new FalconTestExtension();

    @Test
    @DisplayName("A sweep without a threshold runs every repetition, no matter how many fail")
    void sweepWithoutThresholdRunsEveryRepetition() {
        final List<FalconFailureThresholdExtension> repetitions = repetitionsOf("noThreshold");
        assertThat(repetitions).hasSize(3);

        for (final FalconFailureThresholdExtension repetition : repetitions) {
            assertThat(isDisabled(repetition)).isFalse();
            fail(repetition);
        }
    }

    @Test
    @DisplayName("A sweep skips the remaining repetitions once the threshold is reached")
    void sweepStopsOnceThresholdIsReached() {
        final List<FalconFailureThresholdExtension> repetitions = repetitionsOf("thresholdOfTwo");
        assertThat(repetitions).hasSize(5);

        // The first repetition runs and fails, which leaves the sweep one failure short of the threshold.
        assertThat(isDisabled(repetitions.get(0))).isFalse();
        fail(repetitions.get(0));

        // The second repetition still runs, and its failure reaches the threshold.
        assertThat(isDisabled(repetitions.get(1))).isFalse();
        fail(repetitions.get(1));

        // All remaining repetitions are skipped.
        assertThat(isDisabled(repetitions.get(2))).isTrue();
        assertThat(isDisabled(repetitions.get(3))).isTrue();
        assertThat(isDisabled(repetitions.get(4))).isTrue();
    }

    @Test
    @DisplayName("Repetitions that pass do not count towards the threshold")
    void passingRepetitionsDoNotCountTowardsTheThreshold() {
        final List<FalconFailureThresholdExtension> repetitions = repetitionsOf("thresholdOfTwo");

        // The first repetition fails, the next two pass, so the sweep keeps going.
        assertThat(isDisabled(repetitions.get(0))).isFalse();
        fail(repetitions.get(0));
        assertThat(isDisabled(repetitions.get(1))).isFalse();
        assertThat(isDisabled(repetitions.get(2))).isFalse();

        // Only the failure of the fourth repetition reaches the threshold.
        assertThat(isDisabled(repetitions.get(3))).isFalse();
        fail(repetitions.get(3));
        assertThat(isDisabled(repetitions.get(4))).isTrue();
    }

    @Test
    @DisplayName("A replay of a pinned seed has no failure threshold")
    void replayHasNoFailureThreshold() {
        final List<TestTemplateInvocationContext> contexts = extension
                .provideTestTemplateInvocationContexts(contextFor("replayWithThreshold"))
                .toList();

        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().getAdditionalExtensions())
                .noneMatch(FalconFailureThresholdExtension.class::isInstance);
    }

    @Test
    @DisplayName("A threshold outside of the valid range is rejected")
    void invalidThresholdIsRejected() {
        for (final String testMethod : List.of("thresholdOfZero", "thresholdEqualToRepetitions", "negativeThreshold")) {
            assertThatThrownBy(() -> extension
                            .provideTestTemplateInvocationContexts(contextFor(testMethod))
                            .toList())
                    .as("threshold declared by %s", testMethod)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("failure threshold");
        }
    }

    /**
     * Provides the invocation contexts of the given sample method and extracts the failure-threshold extension of every
     * repetition, in sweep order.
     */
    private List<FalconFailureThresholdExtension> repetitionsOf(final String testMethod) {
        return extension
                .provideTestTemplateInvocationContexts(contextFor(testMethod))
                .map(TestTemplateInvocationContext::getAdditionalExtensions)
                .map(extensions -> extensions.stream()
                        .filter(FalconFailureThresholdExtension.class::isInstance)
                        .map(FalconFailureThresholdExtension.class::cast)
                        .findFirst()
                        .orElseThrow())
                .toList();
    }

    /**
     * Creates an extension context that reports the given sample method as the test method.
     */
    private static ExtensionContext contextFor(final String testMethod) {
        final Method method;
        try {
            method = Samples.class.getDeclaredMethod(testMethod);
        } catch (final NoSuchMethodException e) {
            throw new IllegalArgumentException("No sample method named " + testMethod, e);
        }
        final ExtensionContext context = mock(ExtensionContext.class);
        when(context.getElement()).thenReturn(Optional.of(method));
        when(context.getRequiredTestMethod()).thenReturn(method);
        return context;
    }

    /**
     * Reports the failure of the repetition that the given extension belongs to.
     */
    private static void fail(final FalconFailureThresholdExtension repetition) {
        repetition.testFailed(mock(ExtensionContext.class), new AssertionError("simulated failure"));
    }

    /**
     * Evaluates whether the repetition that the given extension belongs to would be skipped.
     */
    private static boolean isDisabled(final FalconFailureThresholdExtension repetition) {
        final ConditionEvaluationResult result = repetition.evaluateExecutionCondition(mock(ExtensionContext.class));
        return result.isDisabled();
    }

    /**
     * Holds the annotation declarations under test. This class is deliberately not a nested test class, so that JUnit
     * does not discover the methods below as tests; they exist only as carriers of {@link FalconTest} declarations.
     */
    private static final class Samples {

        @FalconTest(repetitions = 3)
        void noThreshold() {}

        @FalconTest(repetitions = 5, failureThreshold = 2)
        void thresholdOfTwo() {}

        @FalconTest(repetitions = 5, failureThreshold = 2, randomSeed = 42L)
        void replayWithThreshold() {}

        @FalconTest(repetitions = 5, failureThreshold = 0)
        void thresholdOfZero() {}

        @FalconTest(repetitions = 5, failureThreshold = 5)
        void thresholdEqualToRepetitions() {}

        @FalconTest(repetitions = 5, failureThreshold = -1)
        void negativeThreshold() {}
    }
}
