// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Consumer;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.ReconnectFailureNotification;
import org.hiero.otter.fixtures.result.ReconnectNotification;
import org.hiero.otter.fixtures.result.ReconnectNotificationSubscriber;
import org.hiero.otter.fixtures.result.ReconnectStartNotification;
import org.hiero.otter.fixtures.result.SynchronizationCompleteNotification;

/**
 * Continuous assertions for {@link MultipleNodeReconnectResults}.
 *
 * <p>Please note: If two continuous assertions fail roughly at the same time, it is non-deterministic which one
 * will report the failure first. This is even true when running a test in the Turtle environment.
 * If deterministic behavior is required, please use regular assertions instead of continuous assertions.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeReconnectResultsContinuousAssert
        extends AbstractMultipleNodeContinuousAssertion<
                MultipleNodeReconnectResultsContinuousAssert, MultipleNodeReconnectResults> {

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeReconnectResults}.
     *
     * @param actual the actual {@link MultipleNodeReconnectResults} to assert
     */
    public MultipleNodeReconnectResultsContinuousAssert(@Nullable final MultipleNodeReconnectResults actual) {
        super(actual, MultipleNodeReconnectResultsContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeReconnectResults}.
     *
     * @param actual the {@link MultipleNodeReconnectResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodeReconnectResults}
     */
    @NonNull
    public static MultipleNodeReconnectResultsContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodeReconnectResults actual) {
        return new MultipleNodeReconnectResultsContinuousAssert(actual);
    }

    /**
     * Asserts that the nodes have no failed reconnects.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeReconnectResultsContinuousAssert hasNoFailedReconnects() {
        return checkContinuously((notification) -> {
            switch (notification) {
                case final ReconnectFailureNotification failureNotification ->
                    failWithMessage(
                            "Expected no failed reconnects, but node %s had %n%s",
                            failureNotification.nodeId() == null
                                    ? "unknown"
                                    : failureNotification.nodeId().id(),
                            failureNotification.payload());
                default -> {
                    // Ignore other notifications
                }
            }
        });
    }

    /**
     * Asserts that the node does not attempt to perform any reconnects.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeReconnectResultsContinuousAssert doNotAttemptToReconnect() {
        return checkContinuously((notification) -> {
            switch (notification) {
                case final ReconnectStartNotification startNotification ->
                    failWithMessage(
                            "Expected no attempted reconnects, but node %s had %n%s",
                            startNotification.nodeId() == null
                                    ? "unknown"
                                    : startNotification.nodeId().id(),
                            startNotification.payload());
                default -> {
                    // Ignore other notifications
                }
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
    public MultipleNodeReconnectResultsContinuousAssert haveMaximumReconnectTime(
            @NonNull final Duration maximumReconnectTime) {
        isNotNull();
        return checkContinuously((notification) -> {
            switch (notification) {
                case final SynchronizationCompleteNotification syncNotification ->
                    failWithMessage(
                            "Expected maximum reconnect time to be <%s> but node %s took <%s>%n%s",
                            maximumReconnectTime,
                            syncNotification.nodeId() == null
                                    ? "unknown"
                                    : syncNotification.nodeId().id(),
                            Duration.ofSeconds((long) syncNotification.payload().getTimeInSeconds()),
                            syncNotification.payload());
                default -> {
                    // Ignore other notifications
                }
            }
        });
    }

    /**
     * Asserts that the nodes have a maximum tree initialization time that is less than or equal to the provided time.
     *
     * @param maximumTreeInitializationTime the maximum allowed tree initialization time
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeReconnectResultsContinuousAssert haveMaximumTreeInitializationTime(
            @NonNull final Duration maximumTreeInitializationTime) {
        isNotNull();
        return checkContinuously(notification -> {
            switch (notification) {
                case final SynchronizationCompleteNotification syncNotification ->
                    failWithMessage(
                            "Expected maximum tree initialization time to be <%s> but node %s took <%s> to initialize the tree%n%s",
                            maximumTreeInitializationTime,
                            syncNotification.nodeId() == null
                                    ? "unknown"
                                    : syncNotification.nodeId().id(),
                            Duration.ofSeconds((long) syncNotification.payload().getInitializationTimeInSeconds()),
                            syncNotification.payload());
                default -> {
                    // Ignore other notifications
                }
            }
        });
    }

    private MultipleNodeReconnectResultsContinuousAssert checkContinuously(
            final Consumer<ReconnectNotification<?>> check) {
        isNotNull();

        final ReconnectNotificationSubscriber subscriber = (notification) -> switch (state) {
            case ACTIVE -> {
                if (!suppressedNodeIds.contains(notification.nodeId())) {
                    check.accept(notification);
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
