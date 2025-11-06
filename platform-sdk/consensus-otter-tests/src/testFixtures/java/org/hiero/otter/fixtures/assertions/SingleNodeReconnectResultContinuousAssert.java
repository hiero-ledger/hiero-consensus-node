// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Consumer;
import org.hiero.otter.fixtures.result.ReconnectFailureNotification;
import org.hiero.otter.fixtures.result.ReconnectNotification;
import org.hiero.otter.fixtures.result.ReconnectNotificationSubscriber;
import org.hiero.otter.fixtures.result.ReconnectStartNotification;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.result.SynchronizationCompleteNotification;

/**
 * Continuous assertions for {@link SingleNodeReconnectResult}.
 *
 * <p>Please note: If two continuous assertions fail roughly at the same time, it is non-deterministic which one
 * will report the failure first. This is even true when running a test in the Turtle environment.
 * If deterministic behavior is required, please use regular assertions instead of continuous assertions.
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
     * @return this assertion object for method chaining
     */
    @NonNull
    public static SingleNodeReconnectResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodeReconnectResult actual) {
        return new SingleNodeReconnectResultContinuousAssert(actual);
    }

    /**
     * Asserts that the node has no failed reconnects.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeReconnectResultContinuousAssert hasNoFailedReconnects() {
        return checkContinuously((notification) -> {
            switch (notification) {
                case final ReconnectFailureNotification f ->
                    failWithMessage("Expected no failed reconnects, but found %n%s", f.payload());
                default -> {
                    // Ignore other notifications
                }
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
        return checkContinuously((notification) -> {
            switch (notification) {
                case final ReconnectStartNotification s ->
                    failWithMessage("Expected no attempted reconnects, found %n%s", s.payload());
                default -> {
                    // Ignore other notifications
                }
            }
        });
    }

    /**
     * Asserts that the node has no reconnects that take longer than the provided time.
     *
     * @param maximumReconnectTime the maximum allowed reconnect time
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeReconnectResultContinuousAssert hasMaximumReconnectTime(
            @NonNull final Duration maximumReconnectTime) {
        return checkContinuously((notification) -> {
            switch (notification) {
                case final SynchronizationCompleteNotification s -> {
                    final Duration actualTime =
                            Duration.ofSeconds((long) s.payload().getTimeInSeconds());
                    if (actualTime.compareTo(maximumReconnectTime) > 0) {
                        failWithMessage(
                                "Expected maximum reconnect time to be <%s> was <%s>%n%s",
                                maximumReconnectTime, actualTime, s.payload());
                    }
                }
                default -> {
                    // Ignore other notifications
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
        return checkContinuously((notification) -> {
            switch (notification) {
                case final SynchronizationCompleteNotification s -> {
                    final Duration actualTime =
                            Duration.ofSeconds((long) s.payload().getInitializationTimeInSeconds());
                    if (actualTime.compareTo(maximumTreeInitializationTime) > 0) {
                        failWithMessage(
                                "Expected maximum tree initialization time to be <%s> but it took <%s> to initialize the tree%n%s",
                                maximumTreeInitializationTime, actualTime, s.payload());
                    }
                }
                default -> {
                    // Ignore other notifications
                }
            }
        });
    }

    @NonNull
    private SingleNodeReconnectResultContinuousAssert checkContinuously(
            @NonNull final Consumer<ReconnectNotification<?>> check) {
        isNotNull();

        final ReconnectNotificationSubscriber subscriber = (notification) -> switch (state) {
            case ACTIVE -> {
                check.accept(notification);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
