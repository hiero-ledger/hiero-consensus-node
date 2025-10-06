// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.internal.helpers.Utils.collect;
import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.function.Consumer;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.PlatformStatusSubscriber;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;

/**
 * Continuous assertions for {@link SingleNodePlatformStatusResult}.
 *
 * <p>Please note: If two continuous assertions fail roughly at the same time, it is non-deterministic which one
 * will report the failure first. This is even true when running a test in the Turtle environment.
 * If deterministic behavior is required, please use regular assertions instead of continuous assertions.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodePlatformStatusResultContinuousAssert
        extends AbstractContinuousAssertion<
                SingleNodePlatformStatusResultContinuousAssert, SingleNodePlatformStatusResult> {

    /**
     * Creates a continuous assertion for the given {@link SingleNodePlatformStatusResult}.
     *
     * @param actual the actual {@link SingleNodePlatformStatusResult} to assert
     */
    public SingleNodePlatformStatusResultContinuousAssert(@Nullable final SingleNodePlatformStatusResult actual) {
        super(actual, SingleNodePlatformStatusResultContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link SingleNodePlatformStatusResult}.
     *
     * @param actual the {@link SingleNodePlatformStatusResult} to assert
     * @return a continuous assertion for the given {@link SingleNodePlatformStatusResult}
     */
    @NonNull
    public static SingleNodePlatformStatusResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodePlatformStatusResult actual) {
        return new SingleNodePlatformStatusResultContinuousAssert(actual);
    }

    /**
     * Verifies that none of the provided statuses are entered.
     *
     * @param first the first status to check
     * @param rest additional statuses to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePlatformStatusResultContinuousAssert doesNotEnterAnyStatusesOf(
            @NonNull final PlatformStatus first, @Nullable final PlatformStatus... rest) {
        final Set<PlatformStatus> statuses = collect(first, rest);
        return checkContinuously(status -> {
            if (statuses.contains(status)) {
                failWithMessage("Expected not to enter any of %s statuses, but entered %s", statuses, status);
            }
        });
    }

    /**
     * Verifies that only the provided statuses are entered.
     *
     * @param first the first status to check
     * @param rest additional statuses to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePlatformStatusResultContinuousAssert doesOnlyEnterStatusesOf(
            @NonNull final PlatformStatus first, @Nullable final PlatformStatus... rest) {
        final Set<PlatformStatus> statuses = collect(first, rest);
        return checkContinuously(status -> {
            if (!statuses.contains(status)) {
                failWithMessage("Expected only to enter %s statuses, but entered %s", statuses, status);
            }
        });
    }

    private SingleNodePlatformStatusResultContinuousAssert checkContinuously(final Consumer<PlatformStatus> check) {
        isNotNull();

        final PlatformStatusSubscriber subscriber = (nodeId, status) -> switch (state) {
            case ACTIVE -> {
                check.accept(status);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
