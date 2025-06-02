// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.Level;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Assertion class for {@link MultipleNodeLogResults}.
 *
 * <p>Provides custom assertions for validating log results across multiple nodes.
 */
@SuppressWarnings("unused")
public class MultipleNodeLogResultsAssert extends AbstractAssert<MultipleNodeLogResultsAssert, MultipleNodeLogResults> {

    /**
     * Constructs an assertion for the given {@link MultipleNodeLogResults}.
     *
     * @param actual the actual {@link MultipleNodeLogResults} to assert
     */
    protected MultipleNodeLogResultsAssert(@Nullable final MultipleNodeLogResults actual) {
        super(actual, MultipleNodeLogResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeLogResults}.
     *
     * @param actual the actual {@link MultipleNodeLogResults} to assert
     * @return a new instance of {@link MultipleNodeLogResultsAssert}
     */
    @NonNull
    public static MultipleNodeLogResultsAssert assertThat(@Nullable final MultipleNodeLogResults actual) {
        return new MultipleNodeLogResultsAssert(actual);
    }

    /**
     * Verifies that no log messages with the specified markers exist.
     *
     * @param first the first marker to check
     * @param rest additional markers to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsAssert noMessageWithMarkers(
            @NonNull final LogMarker first, @Nullable final LogMarker... rest) {
        isNotNull();

        for (final SingleNodeLogResult result : actual.results()) {
            OtterAssertions.assertThat(result).noMessageWithMarkers(first, rest);
        }

        return this;
    }

    /**
     * Verifies that no log messages with a level higher than the specified level exist.
     *
     * @param level the maximum log level to allow
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsAssert noMessageWithLevelHigherThan(@NonNull final Level level) {
        isNotNull();

        for (final SingleNodeLogResult result : actual.results()) {
            OtterAssertions.assertThat(result).noMessageWithLevelHigherThan(level);
        }

        return this;
    }

    /**
     * Verifies that no log messages with an error log level exist.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsAssert hasNoErrorLevelMessages() {
        return noMessageWithLevelHigherThan(Level.WARN);
    }
}
