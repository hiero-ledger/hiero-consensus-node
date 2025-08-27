// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.internal.helpers.Utils.collect;
import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.function.BiConsumer;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.PlatformStatusSubscriber;

/**
 * Continuous assertions for {@link MultipleNodePlatformStatusResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodePlatformStatusResultsContinuousAssert
        extends AbstractMultipleNodeContinuousAssertion<
                MultipleNodePlatformStatusResultsContinuousAssert, MultipleNodePlatformStatusResults> {

    /**
     * Creates a continuous assertion for the given {@link MultipleNodePlatformStatusResults}.
     *
     * @param actual the actual {@link MultipleNodePlatformStatusResults} to assert
     */
    public MultipleNodePlatformStatusResultsContinuousAssert(@Nullable final MultipleNodePlatformStatusResults actual) {
        super(actual, MultipleNodePlatformStatusResultsContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link MultipleNodePlatformStatusResults}.
     *
     * @param actual the {@link MultipleNodePlatformStatusResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodePlatformStatusResults}
     */
    @NonNull
    public static MultipleNodePlatformStatusResultsContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodePlatformStatusResults actual) {
        return new MultipleNodePlatformStatusResultsContinuousAssert(actual);
    }

    /**
     * Verifies that none of the provided statuses are entered.
     *
     * @param first the first status to check
     * @param rest additional statuses to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodePlatformStatusResultsContinuousAssert doNotEnterAnyStatusesOf(
            @NonNull final PlatformStatus first, @Nullable final PlatformStatus... rest) {
        final Set<PlatformStatus> statuses = collect(first, rest);
        return checkContinuously((nodeId, status) -> {
            if (statuses.contains(status)) {
                failWithMessage(
                        "Expected not to enter any of %s statuses, but node %s entered %s", statuses, nodeId, status);
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
    public MultipleNodePlatformStatusResultsContinuousAssert doOnlyEnterStatusesOf(
            @NonNull final PlatformStatus first, @Nullable final PlatformStatus... rest) {
        final Set<PlatformStatus> statuses = collect(first, rest);
        return checkContinuously((nodeId, status) -> {
            if (!statuses.contains(status)) {
                failWithMessage("Expected only to enter %s statuses, but node %s entered %s", statuses, nodeId, status);
            }
        });
    }

    private MultipleNodePlatformStatusResultsContinuousAssert checkContinuously(
            @NonNull final BiConsumer<NodeId, PlatformStatus> check) {
        isNotNull();

        final PlatformStatusSubscriber subscriber = (nodeId, status) -> switch (state) {
            case ACTIVE -> {
                if (!suppressedNodeIds.contains(nodeId)) {
                    check.accept(nodeId, status);
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
