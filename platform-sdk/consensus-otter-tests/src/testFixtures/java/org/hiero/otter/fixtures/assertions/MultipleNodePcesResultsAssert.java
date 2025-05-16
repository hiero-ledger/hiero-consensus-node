// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;

/**
 * Assertions for {@link MultipleNodePcesResults}.
 */
@SuppressWarnings("UnusedReturnValue")
public class MultipleNodePcesResultsAssert
        extends AbstractAssert<MultipleNodePcesResultsAssert, MultipleNodePcesResults> {

    /**
     * Creates a new instance of {@link MultipleNodePcesResultsAssert}
     *
     * @param actual the actual {@link MultipleNodePcesResults} to assert
     */
    public MultipleNodePcesResultsAssert(@Nullable final MultipleNodePcesResults actual) {
        super(actual, MultipleNodePcesResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodePcesResults}.
     *
     * @param actual the {@link MultipleNodePcesResults} to assert
     * @return an assertion for the given {@link MultipleNodePcesResults}
     */
    @NonNull
    public static MultipleNodePcesResultsAssert assertThat(@Nullable final MultipleNodePcesResults actual) {
        return new MultipleNodePcesResultsAssert(actual);
    }

    /**
     * Asserts that all events stored in the PCES files of all nodes have a birth round less than the given value.
     *
     * @param expected the expected maximum birth round
     * @return this assertion object for method chaining
     */
    public MultipleNodePcesResultsAssert hasBirthRoundsLessThan(long expected) {
        isNotNull();
        for (final SingleNodePcesResult pcesResult : actual.pcesResults()) {
            OtterAssertions.assertThat(pcesResult).hasBirthRoundsLessThan(expected);
        }
        return this;
    }
}
