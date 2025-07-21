// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * Assertions for {@link SingleNodeReconnectResult}. This class provides methods to assert the results of reconnect
 * operations performed by a single node in the Otter framework.
 */
public class SingleNodeReconnectResultsAssert
        extends AbstractAssert<SingleNodeReconnectResultsAssert, SingleNodeReconnectResult> {

    public SingleNodeReconnectResultsAssert(@Nullable final SingleNodeReconnectResult actual) {
        super(actual, SingleNodeReconnectResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeReconnectResult}.
     *
     * @param actual the {@link SingleNodeReconnectResult} to assert
     * @return an assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public static SingleNodeReconnectResultsAssert assertThat(@Nullable final SingleNodeReconnectResult actual) {
        return new SingleNodeReconnectResultsAssert(actual);
    }

    /**
     * Asserts that the node has not performed any reconnects, meaning both successful and failed reconnects.
     *
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    public SingleNodeReconnectResultsAssert hasNoReconnects() {
        isNotNull();
        if (actual.numSuccessfulReconnects() > 0 || actual.numFailedReconnects() > 0) {
            failWithMessage(
                    "Expected no reconnects but found <%d> successful and <%d> failed reconnects",
                    actual.numSuccessfulReconnects(), actual.numFailedReconnects());
        }
        return this;
    }

    /**
     * Asserts that the node has no failed reconnects.
     *
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    public SingleNodeReconnectResultsAssert hasNoFailedReconnects() {
        isNotNull();
        if (actual.numFailedReconnects() > 0) {
            failWithMessage(
                    "Expected no failed reconnects but found <%d> failed reconnects", actual.numFailedReconnects());
        }
        return this;
    }

    /**
     * Asserts that the node had no more than the expected number of failed reconnects.
     *
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    public SingleNodeReconnectResultsAssert hasMaximumFailedReconnects(final int maximum) {
        isNotNull();
        if (actual.numFailedReconnects() >= maximum) {
            failWithMessage(
                    "Expected maximum failed reconnects to be <%d> but found <%d>",
                    maximum, actual.numFailedReconnects());
        }
        return this;
    }

    /**
     * Asserts that the node has performed exactly the expected number of successful reconnects.
     *
     * @param expected the expected number of successful reconnects
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    public SingleNodeReconnectResultsAssert hasExactSuccessfulReconnects(final int expected) {
        isNotNull();
        if (actual.numSuccessfulReconnects() != expected) {
            failWithMessage(
                    "Expected successful reconnects to be <%d> but found <%d>",
                    expected, actual.numSuccessfulReconnects());
        }
        return this;
    }

    /**
     * Asserts that the node has performed at least the expected number of successful reconnects.
     *
     * @param minimum the minimum number of successful reconnects expected
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    public SingleNodeReconnectResultsAssert hasMinimumSuccessfulReconnects(final int minimum) {
        isNotNull();
        if (actual.numSuccessfulReconnects() < minimum) {
            failWithMessage(
                    "Expected minimum successful reconnects to be <%d> but found <%d>",
                    minimum, actual.numSuccessfulReconnects());
        }
        return this;
    }

    /**
     * Asserts that the node has performed no more than the expected number of successful reconnects.
     *
     * @param maximum the maximum number of successful reconnects expected
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    public SingleNodeReconnectResultsAssert hasMaximumSuccessfulReconnects(final int maximum) {
        isNotNull();
        if (actual.numSuccessfulReconnects() >= maximum) {
            failWithMessage(
                    "Expected maximum successful reconnects to be <%d> but found <%d>",
                    maximum, actual.numSuccessfulReconnects());
        }
        return this;
    }
}
