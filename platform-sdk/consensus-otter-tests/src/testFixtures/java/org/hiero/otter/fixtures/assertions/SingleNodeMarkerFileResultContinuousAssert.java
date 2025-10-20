// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.function.Consumer;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.internal.helpers.Utils;
import org.hiero.otter.fixtures.result.MarkerFileSubscriber;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;

/**
 * Continuous assertions for {@link SingleNodeMarkerFileResult}.
 *
 * <p>Please note: If two continuous assertions fail roughly at the same time, it is non-deterministic which one
 * will report the failure first. This is even true when running a test in the Turtle environment.
 * If deterministic behavior is required, please use regular assertions instead of continuous assertions.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodeMarkerFileResultContinuousAssert
        extends AbstractContinuousAssertion<SingleNodeMarkerFileResultContinuousAssert, SingleNodeMarkerFileResult> {

    /**
     * Creates a continuous assertion for the given {@link SingleNodeMarkerFileResult}.
     *
     * @param actual the actual {@link SingleNodeMarkerFileResult} to assert
     */
    public SingleNodeMarkerFileResultContinuousAssert(@Nullable final SingleNodeMarkerFileResult actual) {
        super(actual, SingleNodeMarkerFileResultContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link SingleNodeMarkerFileResult}.
     *
     * @param actual the {@link SingleNodeMarkerFileResult} to assert
     * @return a continuous assertion for the given {@link SingleNodeMarkerFileResult}
     */
    @NonNull
    public static SingleNodeMarkerFileResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodeMarkerFileResult actual) {
        return new SingleNodeMarkerFileResultContinuousAssert(actual);
    }

    /**
     * Verifies that the node does not write any marker files.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoMarkerFile() {
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasAnyMarkerFile()) {
                failWithMessage("Expected no marker files, but found %s", markerFilesStatus);
            }
        });
    }

    /**
     * Verifies that the node does not write a coin round marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoCoinRoundMarkerFile() {
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasCoinRoundMarkerFile()) {
                failWithMessage("Expected no coin round marker file, but one was written");
            }
        });
    }

    /**
     * Verifies that the node does not write a missing-super-majority marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoMissingSuperMajorityMarkerFile() {
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasMissingSuperMajorityMarkerFile()) {
                failWithMessage("Expected no missing-super-majority marker file, but one was written");
            }
        });
    }

    /**
     * Verifies that the node does not write a missing-judges marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoMissingJudgesMarkerFile() {
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasMissingJudgesMarkerFile()) {
                failWithMessage("Expected no missing-judges marker file, but one was written");
            }
        });
    }

    /**
     * Verifies that the node does not write a consensus exception marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoWriteConsensusExceptionMarkerFile() {
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasConsensusExceptionMarkerFile()) {
                failWithMessage("Expected no consensus exception marker file, but one was written");
            }
        });
    }

    /**
     * Verifies that the node does not write any ISS marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoIssMarkerFile() {
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasAnyIssMarkerFile()) {
                failWithMessage("Expected no ISS marker file, but found: %s", markerFilesStatus);
            }
        });
    }

    /**
     * Verifies that the node does not write an ISS marker file of the specified type.
     *
     * @param issType the type of ISS marker file to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoIssMarkerFileOfType(@NonNull final IssType issType) {
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasIssMarkerFileOfType(issType)) {
                failWithMessage("Expected no ISS marker file of type %s, but one was written", issType);
            }
        });
    }

    /**
     * Verifies that the node does not write any marker files except for the specified ISS type.
     *
     * @param first  the first mandatory type of ISS marker file that is allowed
     * @param rest the other optional types of ISS marker files that are allowed
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultContinuousAssert hasNoMarkerFilesExcept(
            @NonNull final IssType first, @Nullable final IssType... rest) {
        final Set<IssType> issTypes = Utils.collect(first, rest);
        return checkContinuously(markerFilesStatus -> {
            if (markerFilesStatus.hasCoinRoundMarkerFile()) {
                failWithMessage("Expected no coin round marker file, but one was written");
            }
            if (markerFilesStatus.hasMissingSuperMajorityMarkerFile()) {
                failWithMessage("Expected no missing-super-majority marker file, but one was written");
            }
            if (markerFilesStatus.hasMissingJudgesMarkerFile()) {
                failWithMessage("Expected no missing-judges marker file, but one was written");
            }
            if (markerFilesStatus.hasConsensusExceptionMarkerFile()) {
                failWithMessage("Expected no consensus exception marker file, but one was written");
            }
            if (!issTypes.contains(IssType.OTHER_ISS) && markerFilesStatus.hasIssMarkerFileOfType(IssType.OTHER_ISS)) {
                failWithMessage("Expected no ISS marker file of type OTHER_ISS, but one was written");
            }
            if (!issTypes.contains(IssType.SELF_ISS) && markerFilesStatus.hasIssMarkerFileOfType(IssType.SELF_ISS)) {
                failWithMessage("Expected no ISS marker file of type SELF_ISS, but one was written");
            }
            if (!issTypes.contains(IssType.CATASTROPHIC_ISS)
                    && markerFilesStatus.hasIssMarkerFileOfType(IssType.CATASTROPHIC_ISS)) {
                failWithMessage("Expected no ISS marker file of type CATASTROPHIC_ISS, but one was written");
            }
        });
    }

    private SingleNodeMarkerFileResultContinuousAssert checkContinuously(
            @NonNull final Consumer<MarkerFilesStatus> check) {
        isNotNull();

        final MarkerFileSubscriber subscriber = (nodeId, markerFilesStatus) -> switch (state) {
            case ACTIVE -> {
                check.accept(markerFilesStatus);
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
