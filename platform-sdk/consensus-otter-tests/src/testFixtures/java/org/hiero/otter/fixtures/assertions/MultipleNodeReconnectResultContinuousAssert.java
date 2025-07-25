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
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;

/**
 * Continuous assertions for {@link MultipleNodeReconnectResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeReconnectResultContinuousAssert
        extends AbstractMultipleNodeContinuousAssertion<
                MultipleNodeReconnectResultContinuousAssert, MultipleNodeReconnectResults> {

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeReconnectResults}.
     *
     * @param actual the actual {@link MultipleNodeReconnectResults} to assert
     */
    public MultipleNodeReconnectResultContinuousAssert(@Nullable final MultipleNodeReconnectResults actual) {
        super(actual, MultipleNodeReconnectResultContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeReconnectResults}.
     *
     * @param actual the {@link MultipleNodeReconnectResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodeReconnectResults}
     */
    @NonNull
    public static MultipleNodeReconnectResultContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodeReconnectResults actual) {
        return new MultipleNodeReconnectResultContinuousAssert(actual);
    }

    /**
     * Asserts that the nodes have no failed reconnects.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeReconnectResultContinuousAssert hasNoFailedReconnects() {
        return checkContinuously(logEntry -> {
            if (logEntry.message().contains(ReconnectFailurePayload.class.toString())) {
                failWithMessage(
                        "Expected no failed reconnects, but found %s on node %s",
                        logEntry.message(), logEntry.nodeId());
            }
        });
    }

    /**
     * Asserts that the node does not attempt to perform any reconnects.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeReconnectResultContinuousAssert doNotAttemptToReconnect() {
        return checkContinuously((logEntry) -> {
            if (logEntry.message().contains(ReconnectStartPayload.class.toString())) {
                failWithMessage(
                        "Expected no reconnect, but found %s on node %s", logEntry.message(), logEntry.nodeId());
            }
        });
    }

    /**
     * Asserts that the nodes have no reconnects that take longer than the provided time.
     *
     * @param maximumReconnectTime the maximum allowed reconnect time
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeReconnectResultContinuousAssert haveMaximumReconnectTime(
            @NonNull final Duration maximumReconnectTime) {
        isNotNull();
        return checkContinuously(logEntry -> {
            if (logEntry.message().contains(SynchronizationCompletePayload.class.toString())) {
                final SynchronizationCompletePayload payload =
                        parsePayload(SynchronizationCompletePayload.class, logEntry.message());
                if (payload.getTimeInSeconds() > maximumReconnectTime.getSeconds()) {
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
     * Asserts that the nodes have a maximum tree initialization time that is less than or equal to the provided time.
     *
     * @param maximumTreeInitializationTime the maximum allowed tree initialization time
     * @return  this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeReconnectResultContinuousAssert haveMaximumTreeInitializationTime(
            @NonNull final Duration maximumTreeInitializationTime) {
        isNotNull();
        return checkContinuously(logEntry -> {
            if (logEntry.message().contains(SynchronizationCompletePayload.class.toString())) {
                final SynchronizationCompletePayload payload =
                        parsePayload(SynchronizationCompletePayload.class, logEntry.message());
                if (payload.getInitializationTimeInSeconds() > maximumTreeInitializationTime.getSeconds()) {
                    failWithMessage(
                            "Expected maximum tree initialization time to be <%s> but found <%s> on node %s",
                            maximumTreeInitializationTime,
                            Duration.ofSeconds((long) payload.getInitializationTimeInSeconds()),
                            logEntry.nodeId());
                }
            }
        });
    }

    private MultipleNodeReconnectResultContinuousAssert checkContinuously(final Consumer<StructuredLog> check) {
        isNotNull();

        final LogSubscriber subscriber = logEntry -> switch (state) {
            case ACTIVE -> {
                if (!suppressedNodeIds.contains(logEntry.nodeId())) {
                    check.accept(logEntry);
                }
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
