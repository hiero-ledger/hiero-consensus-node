// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.internal.helpers.LogPayloadUtils.parsePayload;
import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.BiConsumer;
import org.hiero.otter.fixtures.result.ReconnectFailurePayloadSubscriber;
import org.hiero.otter.fixtures.result.ReconnectStartPayloadSubscriber;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.result.SynchronizationCompletePayloadSubscriber;

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
        return continuouslyCheckReconnectFailures((failurePayload, nodeId) -> {
            failWithMessage("Expected no failed reconnects, but node %s had %n%s",
                    nodeId == null ? "unknown" : nodeId.id(),
                    failurePayload);
        });
    }

    /**
     * Asserts that the node does attempt to perform any reconnects.
     *
     * @return a continuous assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public SingleNodeReconnectResultContinuousAssert doesNotAttemptToReconnect() {
        return continuouslyCheckReconnectStart((startPayload, nodeId) -> {
            failWithMessage(
                    "Expected no attempted reconnects, but node %s had %n%s",
                    nodeId == null ? "unknown" : nodeId.id(),
                    startPayload);
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
        return continuouslyCheckSynchronizationComplete((syncCompletePayload, nodeId) -> {
            if (syncCompletePayload.getTimeInSeconds() > (double) maximumReconnectTime.getSeconds()) {
                failWithMessage(
                        "Expected maximum reconnect time to be <%s> but node %s took <%s>%n%s",
                        maximumReconnectTime,
                        nodeId == null ? "unknown" : nodeId.id(),
                        Duration.ofSeconds((long) syncCompletePayload.getTimeInSeconds()),
                        syncCompletePayload);
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
        return continuouslyCheckSynchronizationComplete((syncCompletePayload, nodeId) -> {
            if (syncCompletePayload.getInitializationTimeInSeconds()
                    > (double) maximumTreeInitializationTime.getSeconds()) {
                failWithMessage(
                        "Expected maximum tree initialization time to be <%s> but node %s took <%s> to initialize the tree%n%s",
                        maximumTreeInitializationTime,
                        nodeId == null ? "unknown" : nodeId.id(),
                        Duration.ofSeconds((long) syncCompletePayload.getInitializationTimeInSeconds()),
                        syncCompletePayload);
            }
        });
    }

    @NonNull
    private SingleNodeReconnectResultContinuousAssert continuouslyCheckReconnectFailures(
            @NonNull final BiConsumer<ReconnectFailurePayload, NodeId> check) {
        isNotNull();

        final ReconnectFailurePayloadSubscriber subscriber = (payload, nodeId) -> switch (state) {
            case ACTIVE -> {
                check.accept(payload, nodeId);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }

    @NonNull
    private SingleNodeReconnectResultContinuousAssert continuouslyCheckReconnectStart(
            @NonNull final BiConsumer<ReconnectStartPayload, NodeId> check) {
        isNotNull();

        final ReconnectStartPayloadSubscriber subscriber = (payload, nodeId) -> switch (state) {
            case ACTIVE -> {
                check.accept(payload, nodeId);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }

    @NonNull
    private SingleNodeReconnectResultContinuousAssert continuouslyCheckSynchronizationComplete(
            @NonNull final BiConsumer<SynchronizationCompletePayload, NodeId> check) {
        isNotNull();

        final SynchronizationCompletePayloadSubscriber subscriber = (payload, nodeId) -> switch (state) {
            case ACTIVE -> {
                check.accept(payload, nodeId);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
