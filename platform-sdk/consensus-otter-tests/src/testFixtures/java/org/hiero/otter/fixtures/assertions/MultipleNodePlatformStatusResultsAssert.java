// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;

/**
 * Assertions for {@link MultipleNodePlatformStatusResults}.
 */
@SuppressWarnings("UnusedReturnValue")
public class MultipleNodePlatformStatusResultsAssert
        extends AbstractAssert<MultipleNodePlatformStatusResultsAssert, MultipleNodePlatformStatusResults> {

    /**
     * Creates a new instance of {@link MultipleNodePlatformStatusResultsAssert}
     *
     * @param actual the actual {@link MultipleNodePlatformStatusResults} to assert
     */
    public MultipleNodePlatformStatusResultsAssert(@Nullable final MultipleNodePlatformStatusResults actual) {
        super(actual, MultipleNodePlatformStatusResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodePlatformStatusResults}.
     *
     * @param actual the {@link MultipleNodePlatformStatusResults} to assert
     * @return an assertion for the given {@link MultipleNodePlatformStatusResults}
     */
    @NonNull
    public static MultipleNodePlatformStatusResultsAssert assertThat(
            @Nullable final MultipleNodePlatformStatusResults actual) {
        return new MultipleNodePlatformStatusResultsAssert(actual);
    }

    /**
     * Verifies that all nodes' statuses went exactly through specified steps.
     *
     * @param first the first expected step
     * @param rest additional expected steps
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodePlatformStatusResultsAssert haveSteps(
            @NonNull final StatusProgressionStep first, @Nullable final StatusProgressionStep... rest) {
        isNotNull();
        for (final SingleNodePlatformStatusResults statusProgression : actual.statusProgressions()) {
            OtterAssertions.assertThat(statusProgression).hasSteps(first, rest);
        }
        return this;
    }
}
