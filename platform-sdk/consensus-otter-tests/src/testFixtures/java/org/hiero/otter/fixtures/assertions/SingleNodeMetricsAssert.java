// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

// SPDX-License-Identifier: Apache-2.0

import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.result.SingleNodeMetricsResult;

/**
 * Assertion class for {@link SingleNodeMetricsResult}.
 *
 * <p>Provides custom assertions for validating metric results for one node.
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class SingleNodeMetricsAssert extends AbstractAssert<SingleNodeMetricsAssert, SingleNodeMetricsResult> {

    /**
     * Constructs an assertion for the given {@link SingleNodeMetricsResult}.
     *
     * @param actual the actual {@link SingleNodeMetricsResult} to assert
     */
    protected SingleNodeMetricsAssert(@Nullable final SingleNodeMetricsResult actual) {
        super(actual, SingleNodeMetricsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeMetricsResult}.
     *
     * @param actual the actual {@link SingleNodeMetricsResult} to assert
     * @return a new instance of {@link SingleNodeMetricsResult}
     */
    public static SingleNodeMetricsAssert assertThat(@Nullable final SingleNodeMetricsResult actual) {
        return new SingleNodeMetricsAssert(actual);
    }

    /**
     * Verifies that the specified metric value was at no point higher than the given threshold.
     *
     * @param threshold the exclusive upper bound
     * @return the {@link SingleNodeMetricsResult} instance
     */
    public SingleNodeMetricsAssert neverExceeds(final double threshold) {
        isNotNull();
        if (actual.stats().getMax() > threshold) {
            failWithMessage(
                    "Metric %s exceeded threshold %.3f. Stats: %s", actual.identifier(), threshold, actual.stats());
        }
        return this;
    }
}
