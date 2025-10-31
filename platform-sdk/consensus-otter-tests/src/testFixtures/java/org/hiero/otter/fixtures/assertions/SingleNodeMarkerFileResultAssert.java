// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.internal.helpers.Utils;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;

/**
 * Assertions for {@link SingleNodeMarkerFileResult}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodeMarkerFileResultAssert
        extends AbstractAssert<SingleNodeMarkerFileResultAssert, SingleNodeMarkerFileResult> {

    /**
     * Creates a new instance of {@link SingleNodeMarkerFileResultAssert}.
     *
     * @param actual the actual {@link SingleNodeMarkerFileResult} to assert
     */
    public SingleNodeMarkerFileResultAssert(@Nullable final SingleNodeMarkerFileResult actual) {
        super(actual, SingleNodeMarkerFileResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeMarkerFileResult}.
     *
     * @param actual the {@link SingleNodeMarkerFileResult} to assert
     * @return an assertion for the given {@link SingleNodeMarkerFileResult}
     */
    @NonNull
    public static SingleNodeMarkerFileResultAssert assertThat(@Nullable final SingleNodeMarkerFileResult actual) {
        return new SingleNodeMarkerFileResultAssert(actual);
    }

    /**
     * Verifies that the node does not have any marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoMarkerFile() {
        isNotNull();

        if (actual.status().hasAnyMarkerFile()) {
            failWithMessage(
                    "Expected no marker files, but node %s wrote at least one: %s", actual.nodeId(), actual.status());
        }

        return this;
    }

    /**
     * Verifies that the node has any marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasAnyMarkerFile() {
        isNotNull();

        if (!actual.status().hasAnyMarkerFile()) {
            failWithMessage("Expected at least one marker file, but node %s wrote none", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node has no coin round marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoCoinRoundMarkerFile() {
        isNotNull();

        if (actual.status().hasCoinRoundMarkerFile()) {
            failWithMessage("Expected no coin round marker file, but node %s wrote one", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node has a coin round marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasCoinRoundMarkerFile() {
        isNotNull();

        if (!actual.status().hasCoinRoundMarkerFile()) {
            failWithMessage("Expected a coin round marker file, but node %s wrote none", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node does not have a missing-super-majority marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoMissingSuperMajorityMarkerFile() {
        isNotNull();

        if (actual.status().hasMissingSuperMajorityMarkerFile()) {
            failWithMessage("Expected no missing-super-majority marker file, but node %s wrote one", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node has a missing-super-majority marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasMissingSuperMajorityMarkerFile() {
        isNotNull();

        if (!actual.status().hasMissingSuperMajorityMarkerFile()) {
            failWithMessage("Expected a missing-super-majority marker file, but node %s wrote none", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node does not have a missing-judges marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoMissingJudgesMarkerFile() {
        isNotNull();

        if (actual.status().hasMissingJudgesMarkerFile()) {
            failWithMessage("Expected no missing-judges marker file, but node %s wrote one", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node has a missing-judges marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasMissingJudgesMarkerFile() {
        isNotNull();

        if (!actual.status().hasMissingJudgesMarkerFile()) {
            failWithMessage("Expected a missing-judges marker file, but node %s wrote none", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node does not have a consensus exception marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoConsensusExceptionMarkerFile() {
        isNotNull();

        if (actual.status().hasConsensusExceptionMarkerFile()) {
            failWithMessage("Expected no consensus exception marker file, but node %s wrote one", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node has a consensus exception marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasConsensusExceptionMarkerFile() {
        isNotNull();

        if (!actual.status().hasConsensusExceptionMarkerFile()) {
            failWithMessage("Expected a consensus exception marker file, but node %s wrote none", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node does not have any ISS marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoIssMarkerFile() {
        isNotNull();

        if (actual.status().hasAnyIssMarkerFile()) {
            failWithMessage(
                    "Expected no ISS marker files, but node %s wrote at least one: %s",
                    actual.nodeId(), actual.status());
        }

        return this;
    }

    /**
     * Verifies that the node has any ISS marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasAnyIssMarkerFile() {
        isNotNull();

        if (!actual.status().hasAnyIssMarkerFile()) {
            failWithMessage("Expected any ISS marker files, but node %s wrote none", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node does not have an ISS marker file of a given {@link IssType}.
     *
     * @param issType the type of ISS marker file to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoIssMarkerFileOfType(@NonNull final IssType issType) {
        isNotNull();

        if (actual.status().hasIssMarkerFileOfType(issType)) {
            failWithMessage("Expected no ISS marker file of type %s, but node %s wrote one", issType, actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node has an ISS marker file of a given {@link IssType}.
     *
     * @param issType the type of ISS marker file to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasIssMarkerFileOfType(@NonNull final IssType issType) {
        isNotNull();

        if (!actual.status().hasIssMarkerFileOfType(issType)) {
            failWithMessage("Expected an ISS marker file of type %s, but node %s wrote none", issType, actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node has no marker files except for the given ISS type.
     *
     * @param first  the first mandatory type of ISS marker file that is allowed
     * @param rest the other optional types of ISS marker files that are allowed
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoMarkerFilesExcept(
            @NonNull final IssType first, @Nullable final IssType... rest) {
        final Set<IssType> issTypes = Utils.collect(first, rest);
        hasNoCoinRoundMarkerFile();
        hasNoMissingSuperMajorityMarkerFile();
        hasNoMissingJudgesMarkerFile();
        hasNoConsensusExceptionMarkerFile();
        if (!issTypes.contains(IssType.OTHER_ISS)) {
            hasNoIssMarkerFileOfType(IssType.OTHER_ISS);
        }
        if (!issTypes.contains(IssType.SELF_ISS)) {
            hasNoIssMarkerFileOfType(IssType.SELF_ISS);
        }
        if (!issTypes.contains(IssType.CATASTROPHIC_ISS)) {
            hasNoIssMarkerFileOfType(IssType.CATASTROPHIC_ISS);
        }
        return this;
    }
}
