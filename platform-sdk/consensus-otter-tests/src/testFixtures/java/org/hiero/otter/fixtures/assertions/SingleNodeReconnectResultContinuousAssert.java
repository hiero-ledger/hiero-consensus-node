// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.internal.helpers.LogPayloadUtils.parsePayload;
import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Consumer;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * Continuous assertions for {@link SingleNodeReconnectResult}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodeReconnectResultContinuousAssert
        extends AbstractContinuousAssertion<SingleNodeReconnectResultContinuousAssert, SingleNodeReconnectResult> {

    /**
     * Creates a continuous assertion for the given {@link SingleNodePlatformStatusResult}.
     *
     * @param actual the actual {@link SingleNodePlatformStatusResult} to assert
     */
    public SingleNodeReconnectResultContinuousAssert(@Nullable final SingleNodeReconnectResult actual) {
        super(actual, SingleNodeReconnectResultContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link SingleNodePlatformStatusResult}.
     *
     * @param actual the {@link SingleNodePlatformStatusResult} to assert
     * @return  this assertion object for method chaining
     */
    @NonNull
    public static SingleNodeReconnectResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodeReconnectResult actual) {
        return new SingleNodeReconnectResultContinuousAssert(actual);
    }

    /**
     * Asserts that the node has no failed reconnects.
     *
     * @return  this assertion object for method chaining
     */
    @NonNull
    public SingleNodeReconnectResultContinuousAssert hasNoFailedReconnects() {
        return checkContinuously(logEntry -> {
            if (logEntry.message().contains(ReconnectFailurePayload.class.toString())) {
                failWithMessage(
                        "Expected no failed reconnects, but found %s on node %s",
                        logEntry.message(), logEntry.nodeId());
            }
        });
    }

    /**
     * Asserts that the node does attempt to perform any reconnects.
     *
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public SingleNodeReconnectResultContinuousAssert doesNotAttemptToReconnect() {
        return checkContinuously(logEntry -> {
            if (logEntry.message().contains(ReconnectStartPayload.class.toString())) {
                failWithMessage(
                        "Expected no attempted reconnects, but found %s on node %s",
                        logEntry.message(), logEntry.nodeId());
            }
        });
    }

    /**
     * Asserts that the node has no reconnects that take longer than the provided time.
     *
     * @param maximumReconnectTime the maximum allowed reconnect time
     * @return  this assertion object for method chaining
     */
    @NonNull
    public SingleNodeReconnectResultContinuousAssert hasMaximumReconnectTime(
            @NonNull final Duration maximumReconnectTime) {
        return checkContinuously(logEntry -> {
            if (logEntry.message().contains(SynchronizationCompletePayload.class.toString())) {
                final SynchronizationCompletePayload payload =
                        parsePayload(SynchronizationCompletePayload.class, logEntry.message());
                if (payload.getTimeInSeconds() > (double) maximumReconnectTime.getSeconds()) {
                    failWithMessage(
                            "Expected maximum reconnect time to be <%s> but found <%s> on node %s",
                            maximumReconnectTime,
                            Duration.ofSeconds((long) payload.getTimeInSeconds()),
                            logEntry.nodeId());
                }
            }
        });
    }

    /**
     * Asserts that the node has no reconnects that take longer than the provided time to initialize the tree.
     *
     * @param maximumTreeInitializationTime the maximum allowed tree initialization time
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeReconnectResultContinuousAssert hasMaximumTreeInitializationTime(
            final Duration maximumTreeInitializationTime) {
        return checkContinuously(logEntry -> {
            if (logEntry.message().contains(SynchronizationCompletePayload.class.toString())) {
                final SynchronizationCompletePayload payload =
                        parsePayload(SynchronizationCompletePayload.class, logEntry.message());
                if (payload.getInitializationTimeInSeconds() > (double) maximumTreeInitializationTime.getSeconds()) {
                    failWithMessage(
                            "Expected maximum tree initialization time to be <%s> but found <%s> on node %s",
                            maximumTreeInitializationTime,
                            Duration.ofSeconds((long) payload.getInitializationTimeInSeconds()),
                            logEntry.nodeId());
                }
            }
        });
    }

    @NonNull
    private SingleNodeReconnectResultContinuousAssert checkContinuously(@NonNull final Consumer<StructuredLog> check) {
        isNotNull();

        final LogSubscriber subscriber = logEntry -> switch (state) {
            case ACTIVE -> {
                check.accept(logEntry);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
