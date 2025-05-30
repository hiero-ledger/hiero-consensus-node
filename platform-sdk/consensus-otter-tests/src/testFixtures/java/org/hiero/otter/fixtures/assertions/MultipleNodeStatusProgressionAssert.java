// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.result.MultipleNodeStatusProgression;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Assertions for {@link MultipleNodeStatusProgression}.
 */
@SuppressWarnings("UnusedReturnValue")
public class MultipleNodeStatusProgressionAssert
        extends AbstractAssert<MultipleNodeStatusProgressionAssert, MultipleNodeStatusProgression> {

    /**
     * Creates a new instance of {@link MultipleNodeStatusProgressionAssert}
     *
     * @param actual the actual {@link MultipleNodeStatusProgression} to assert
     */
    public MultipleNodeStatusProgressionAssert(@Nullable final MultipleNodeStatusProgression actual) {
        super(actual, MultipleNodeStatusProgressionAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeStatusProgression}.
     *
     * @param actual the {@link MultipleNodeStatusProgression} to assert
     * @return an assertion for the given {@link MultipleNodeStatusProgression}
     */
    @NonNull
    public static MultipleNodeStatusProgressionAssert assertThat(@Nullable final MultipleNodeStatusProgression actual) {
        return new MultipleNodeStatusProgressionAssert(actual);
    }

    /**
     * Verifies that all nodes' statuses went exactly through specified steps.
     *
     * @param first the first expected step
     * @param rest additional expected steps
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeStatusProgressionAssert hasSteps(
            @NonNull final StatusProgressionStep first, @Nullable final StatusProgressionStep... rest) {
        isNotNull();
        for (final SingleNodeStatusProgression statusProgression : actual.statusProgressions()) {
            OtterAssertions.assertThat(statusProgression).hasSteps(first, rest);
        }
        return this;
    }
}
