// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

// SPDX-License-Identifier: Apache-2.0

import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.result.MultipleNodeMetricsResults;

/**
 * Assertion class for {@link MultipleNodeMetricsResults}.
 *
 * <p>Provides custom assertions for validating metric results across multiple nodes.
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class MultipleNodeMetricsAssert extends AbstractAssert<MultipleNodeMetricsAssert, MultipleNodeMetricsResults> {

    /**
     * Constructs an assertion for the given {@link MultipleNodeMetricsResults}.
     *
     * @param actual the actual {@link MultipleNodeMetricsResults} to assert
     */
    protected MultipleNodeMetricsAssert(@Nullable final MultipleNodeMetricsResults actual) {
        super(actual, MultipleNodeMetricsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeMetricsResults}.
     *
     * @param actual the actual {@link MultipleNodeMetricsResults} to assert
     * @return a new instance of {@link MultipleNodeMetricsAssert}
     */
    public static MultipleNodeMetricsAssert assertThat(@Nullable final MultipleNodeMetricsResults actual) {
        return new MultipleNodeMetricsAssert(actual);
    }

    /**
     * Verifies that the specified metric never exceeded the given threshold on any node.
     *
     * @param threshold the threshold for the metric
     * @return the {@link MultipleNodeMetricsResults} instance
     */
    public MultipleNodeMetricsAssert neverExceeds(final double threshold) {
        isNotNull();
        actual.results().forEach(result -> {
            SingleNodeMetricsAssert.assertThat(result).neverExceeds(threshold);
        });
        return this;
    }
}
