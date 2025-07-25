// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.notification.IssNotification.IssType;
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
     * Verifies that the node does not have a no-super-majority marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoNoSuperMajorityMarkerFile() {
        isNotNull();

        if (actual.status().hasNoSuperMajorityMarkerFile()) {
            failWithMessage("Expected no no-super-majority marker file, but node %s wrote one", actual.nodeId());
        }

        return this;
    }

    /**
     * Verifies that the node does not have a no-judges marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoNoJudgesMarkerFile() {
        isNotNull();

        if (actual.status().hasNoJudgesMarkerFile()) {
            failWithMessage("Expected no no-judges marker file, but node %s wrote one", actual.nodeId());
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
     * Verifies that the node does not have any ISS marker file.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeMarkerFileResultAssert hasNoISSMarkerFile() {
        isNotNull();

        if (actual.status().hasAnyISSMarkerFile()) {
            failWithMessage(
                    "Expected no ISS marker files, but node %s wrote at least one: %s",
                    actual.nodeId(), actual.status());
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
    public SingleNodeMarkerFileResultAssert hasNoISSMarkerFileOfType(@NonNull final IssType issType) {
        isNotNull();

        if (actual.status().hasISSMarkerFileOfType(issType)) {
            failWithMessage("Expected no ISS marker file of type %s, but node %s wrote one", issType, actual.nodeId());
        }

        return this;
    }
}
