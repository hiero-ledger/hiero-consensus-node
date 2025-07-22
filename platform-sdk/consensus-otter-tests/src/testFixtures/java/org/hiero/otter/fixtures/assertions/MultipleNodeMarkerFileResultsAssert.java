// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.result.MultipleNodeMarkerFileResults;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;

/**
 * Assertions for {@link MultipleNodeMarkerFileResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeMarkerFileResultsAssert
        extends AbstractAssert<MultipleNodeMarkerFileResultsAssert, MultipleNodeMarkerFileResults> {

    /**
     * Creates a new instance of {@link MultipleNodeMarkerFileResultsAssert}.
     *
     * @param actual the actual {@link MultipleNodeMarkerFileResults} to assert
     */
    public MultipleNodeMarkerFileResultsAssert(@Nullable final MultipleNodeMarkerFileResults actual) {
        super(actual, MultipleNodeMarkerFileResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeMarkerFileResults}.
     *
     * @param actual the {@link MultipleNodeMarkerFileResults} to assert
     * @return an assertion for the given {@link MultipleNodeMarkerFileResults}
     */
    @NonNull
    public static MultipleNodeMarkerFileResultsAssert assertThat(@Nullable final MultipleNodeMarkerFileResults actual) {
        return new MultipleNodeMarkerFileResultsAssert(actual);
    }

    /**
     * Verifies that the nodes do not have any marker files.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsAssert haveNoMarkerFiles() {
        isNotNull();

        for (final SingleNodeMarkerFileResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasNoMarkerFile();
        }

        return this;
    }

    /**
     * Verifies that the nodes do not have coin round marker files.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsAssert haveNoCoinRoundMarkerFile() {
        isNotNull();

        for (final SingleNodeMarkerFileResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasNoCoinRoundMarkerFile();
        }

        return this;
    }

    /**
     * Verifies that the nodes do not have no-super-majority marker files.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsAssert haveNoNoSuperMajorityMarkerFile() {
        isNotNull();

        for (final SingleNodeMarkerFileResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasNoNoSuperMajorityMarkerFile();
        }

        return this;
    }

    /**
     * Verifies that the nodes do not have no-judges marker files.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsAssert haveNoNoJudgesMarkerFile() {
        isNotNull();

        for (final SingleNodeMarkerFileResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasNoNoJudgesMarkerFile();
        }

        return this;
    }

    /**
     * Verifies that the nodes do not have consensus exception marker files.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsAssert haveNoConsensusExceptionMarkerFile() {
        isNotNull();

        for (final SingleNodeMarkerFileResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasNoConsensusExceptionMarkerFile();
        }

        return this;
    }

    /**
     * Verifies that the nodes do not have any ISS marker files.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsAssert haveNoISSMarkerFile() {
        isNotNull();

        for (final SingleNodeMarkerFileResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasNoISSMarkerFile();
        }

        return this;
    }

    /**
     * Verifies that the nodes do not have ISS marker files of type {@link IssType#OTHER_ISS}.
     *
     * @param issType the type of ISS marker file to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeMarkerFileResultsAssert haveNoISSMarkerFileOfType(@NonNull final IssType issType) {
        isNotNull();

        for (final SingleNodeMarkerFileResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasNoISSMarkerFileOfType(issType);
        }

        return this;
    }
}
