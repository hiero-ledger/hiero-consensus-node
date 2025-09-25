// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * Assertions for {@link SingleNodeReconnectResult}. This class provides methods to assert the results of reconnect
 * operations performed by a single node in the Otter framework.
 */
public class SingleNodeReconnectResultAssert
        extends AbstractAssert<SingleNodeReconnectResultAssert, SingleNodeReconnectResult> {

    public SingleNodeReconnectResultAssert(@Nullable final SingleNodeReconnectResult actual) {
        super(actual, SingleNodeReconnectResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeReconnectResult}.
     *
     * @param actual the {@link SingleNodeReconnectResult} to assert
     * @return an assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public static SingleNodeReconnectResultAssert assertThat(@Nullable final SingleNodeReconnectResult actual) {
        return new SingleNodeReconnectResultAssert(actual);
    }

    /**
     * Asserts that the node has not performed any reconnects, meaning both successful and failed reconnects.
     *
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public SingleNodeReconnectResultAssert hasNoReconnects() {
        isNotNull();
        if (actual.numSuccessfulReconnects() > 0 || actual.numFailedReconnects() > 0) {
            failWithMessage(
                    "Expected no reconnects but found <%d> successful and <%d> failed reconnects on node %s",
                    actual.numSuccessfulReconnects(),
                    actual.numFailedReconnects(),
                    actual.nodeId().id());
        }
        return this;
    }

    /**
     * Asserts that the node has no failed reconnects.
     *
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public SingleNodeReconnectResultAssert hasNoFailedReconnects() {
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
    @NonNull
    public SingleNodeReconnectResultAssert hasMaximumFailedReconnects(final int maximum) {
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
    @NonNull
    public SingleNodeReconnectResultAssert hasExactSuccessfulReconnects(final int expected) {
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
    @NonNull
    public SingleNodeReconnectResultAssert hasMinimumSuccessfulReconnects(final int minimum) {
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
    @NonNull
    public SingleNodeReconnectResultAssert hasMaximumSuccessfulReconnects(final int maximum) {
        isNotNull();
        if (actual.numSuccessfulReconnects() >= maximum) {
            failWithMessage(
                    "Expected maximum successful reconnects to be <%d> but found <%d>",
                    maximum, actual.numSuccessfulReconnects());
        }
        return this;
    }

    /**
     * Asserts that the node took no longer than the provided time to complete any of its reconnects.
     *
     * <p>If no reconnects occurred, this check will pass.</p>
     *
     * @param maximumReconnectTime the maximum allowed reconnect time
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public SingleNodeReconnectResultAssert hasMaximumReconnectTime(@NonNull final Duration maximumReconnectTime) {
        isNotNull();
        final List<SynchronizationCompletePayload> payloads = actual.getSynchronizationCompletePayloads();
        payloads.forEach(payload -> {
            if (payload.getTimeInSeconds() > maximumReconnectTime.getSeconds()) {
                failWithMessage(
                        "Expected maximum reconnect time to be <%s> but found <%s>",
                        maximumReconnectTime, Duration.ofSeconds((long) payload.getTimeInSeconds()));
            }
        });
        return this;
    }

    /**
     * Asserts that the node took no longer than the provided time to complete tree initialization after any of its
     * reconnects.
     *
     * <p>If no reconnects occurred, this check will pass.</p>
     *
     * @param maximumTreeInitializationTime the maximum allowed tree initialization time
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public SingleNodeReconnectResultAssert hasMaximumTreeInitializationTime(
            @NonNull final Duration maximumTreeInitializationTime) {
        isNotNull();
        final List<SynchronizationCompletePayload> payloads = actual.getSynchronizationCompletePayloads();
        payloads.forEach(payload -> {
            if (payload.getInitializationTimeInSeconds() > maximumTreeInitializationTime.getSeconds()) {
                failWithMessage(
                        "Expected maximum tree initialization time to be <%s> but found <%s>",
                        maximumTreeInitializationTime,
                        Duration.ofSeconds((long) payload.getInitializationTimeInSeconds()));
            }
        });
        return this;
    }
}
