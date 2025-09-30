// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.BiConsumer;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.result.MarkerFileSubscriber;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;
import org.hiero.otter.fixtures.result.MultipleNodeMarkerFileResults;

/**
 * Continuous assertions for {@link MultipleNodeMarkerFileResults}.
 *
 * <p>Please note: If two continuous assertions fail roughly at the same time, it is non-deterministic which one
 * will report the failure first. This is even true when running a test in the Turtle environment.
 * If deterministic behavior is required, please use regular assertions instead of continuous assertions.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeMarkerFileResultsContinuousAssert
        extends AbstractMultipleNodeContinuousAssertion<
                MultipleNodeMarkerFileResultsContinuousAssert, MultipleNodeMarkerFileResults> {

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeMarkerFileResults}.
     *
     * @param actual the actual {@link MultipleNodeMarkerFileResults} to assert
     */
    public MultipleNodeMarkerFileResultsContinuousAssert(@Nullable final MultipleNodeMarkerFileResults actual) {
        super(actual, MultipleNodeMarkerFileResultsContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeMarkerFileResults}.
     *
     * @param actual the {@link MultipleNodeMarkerFileResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodeMarkerFileResults}
     */
    @NonNull
    public static MultipleNodeMarkerFileResultsContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodeMarkerFileResults actual) {
        return new MultipleNodeMarkerFileResultsContinuousAssert(actual);
    }

    /**
     * Verifies that the nodes write no marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsContinuousAssert haveNoMarkerFiles() {
        return checkContinuously((nodeId, markerFileStatus) -> {
            if (markerFileStatus.hasAnyMarkerFile()) {
                failWithMessage(
                        "Expected no marker file, but node %s wrote at least one: %s", nodeId, markerFileStatus);
            }
        });
    }

    /**
     * Verifies that the nodes do not write a coin round marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsContinuousAssert haveNoCoinRoundMarkerFiles() {
        return checkContinuously((nodeId, markerFileStatus) -> {
            if (markerFileStatus.hasCoinRoundMarkerFile()) {
                failWithMessage("Expected no coin round marker file, but node %s wrote one", nodeId);
            }
        });
    }

    /**
     * Verifies that the nodes do not write a no-super-majority marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsContinuousAssert haveNoNoSuperMajorityMarkerFiles() {
        return checkContinuously((nodeId, markerFileStatus) -> {
            if (markerFileStatus.hasNoSuperMajorityMarkerFile()) {
                failWithMessage("Expected no no-super-majority marker file, but node %s wrote one", nodeId);
            }
        });
    }

    /**
     * Verifies that the nodes do not write a no-judges marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsContinuousAssert haveNoNoJudgesMarkerFiles() {
        return checkContinuously((nodeId, markerFileStatus) -> {
            if (markerFileStatus.hasNoJudgesMarkerFile()) {
                failWithMessage("Expected no no-judges marker file, but node %s wrote one", nodeId);
            }
        });
    }

    /**
     * Verifies that the nodes do not write a consensus exception marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsContinuousAssert haveNoConsensusExceptionMarkerFiles() {
        return checkContinuously((nodeId, markerFileStatus) -> {
            if (markerFileStatus.hasConsensusExceptionMarkerFile()) {
                failWithMessage("Expected no consensus exception marker file, but node %s wrote one", nodeId);
            }
        });
    }

    /**
     * Verifies that the nodes do not write any ISS marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsContinuousAssert haveNoIssMarkerFiles() {
        return checkContinuously((nodeId, markerFileStatus) -> {
            if (markerFileStatus.hasAnyISSMarkerFile()) {
                failWithMessage(
                        "Expected no ISS marker file, but node %s wrote at least one: %s", nodeId, markerFileStatus);
            }
        });
    }

    /**
     * Verifies that the nodes do not write an ISS marker file of the given type.
     *
     * @param issType the type of ISS marker file to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsContinuousAssert haveNoIssMarkerFilesOfType(@NonNull final IssType issType) {
        return checkContinuously((nodeId, markerFileStatus) -> {
            if (markerFileStatus.hasISSMarkerFileOfType(issType)) {
                failWithMessage("Expected no ISS marker file of type '%s', but node %s wrote one", issType, nodeId);
            }
        });
    }

    private MultipleNodeMarkerFileResultsContinuousAssert checkContinuously(
            final BiConsumer<NodeId, MarkerFilesStatus> check) {
        isNotNull();

        final MarkerFileSubscriber subscriber = (nodeId, markerFilesStatus) -> switch (state) {
            case ACTIVE -> {
                check.accept(nodeId, markerFilesStatus);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
