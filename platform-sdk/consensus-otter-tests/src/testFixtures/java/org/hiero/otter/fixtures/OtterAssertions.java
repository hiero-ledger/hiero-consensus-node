// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.Assertions;
import org.hiero.otter.fixtures.assertions.MultipleNodeConsensusResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodeConsensusResultsContinuousAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodeLogResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodePcesResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodePlatformStatusResultsAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeConsensusResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeLogResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodePcesResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodePlatformStatusResultAssert;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;

/**
 * This class contains all {@code assertThat()} methods for test results of the Otter framework.
 */
public class OtterAssertions extends Assertions {

    private OtterAssertions() {}

    /**
     * Creates an assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param actual the {@link SingleNodeConsensusResult} to assert
     * @return an assertion for the given {@link SingleNodeConsensusResult}
     */
    @NonNull
    public static SingleNodeConsensusResultAssert assertThat(@Nullable final SingleNodeConsensusResult actual) {
        return SingleNodeConsensusResultAssert.assertThat(actual);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param actual the {@link MultipleNodeConsensusResults} to assert
     * @return an assertion for the given {@link MultipleNodeConsensusResults}
     */
    @NonNull
    public static MultipleNodeConsensusResultsAssert assertThat(@Nullable final MultipleNodeConsensusResults actual) {
        return MultipleNodeConsensusResultsAssert.assertThat(actual);
    }

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param actual the {@link MultipleNodeConsensusResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodeConsensusResults}
     */
    @NonNull
    public static MultipleNodeConsensusResultsContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodeConsensusResults actual) {
        return MultipleNodeConsensusResultsContinuousAssert.assertContinuouslyThat(actual);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeLogResult}.
     *
     * @param actual the {@link SingleNodeLogResult} to assert
     * @return an assertion for the given {@link SingleNodeLogResult}
     */
    @NonNull
    public static SingleNodeLogResultAssert assertThat(@Nullable final SingleNodeLogResult actual) {
        return SingleNodeLogResultAssert.assertThat(actual);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeLogResults}.
     *
     * @param actual the {@link MultipleNodeLogResults} to assert
     * @return an assertion for the given {@link MultipleNodeLogResults}
     */
    @NonNull
    public static MultipleNodeLogResultsAssert assertThat(@Nullable final MultipleNodeLogResults actual) {
        return MultipleNodeLogResultsAssert.assertThat(actual);
    }

    /**
     * Creates an assertion for the given {@link SingleNodePlatformStatusResults}.
     *
     * @param actual the {@link SingleNodePlatformStatusResults} to assert
     * @return an assertion for the given {@link SingleNodePlatformStatusResults}
     */
    @NonNull
    public static SingleNodePlatformStatusResultAssert assertThat(
            @Nullable final SingleNodePlatformStatusResults actual) {
        return SingleNodePlatformStatusResultAssert.assertThat(actual);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodePlatformStatusResults}.
     *
     * @param actual the {@link MultipleNodePlatformStatusResults} to assert
     * @return an assertion for the given {@link MultipleNodePlatformStatusResults}
     */
    @NonNull
    public static MultipleNodePlatformStatusResultsAssert assertThat(
            @Nullable final MultipleNodePlatformStatusResults actual) {
        return MultipleNodePlatformStatusResultsAssert.assertThat(actual);
    }

    /**
     * Creates an assertion for the given {@link SingleNodePcesResult}.
     *
     * @param actual the {@link SingleNodePcesResult} to assert
     * @return an assertion for the given {@link SingleNodePcesResult}
     */
    @NonNull
    public static SingleNodePcesResultAssert assertThat(@Nullable final SingleNodePcesResult actual) {
        return SingleNodePcesResultAssert.assertThat(actual);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodePcesResults}.
     *
     * @param actual the {@link MultipleNodePcesResults} to assert
     * @return an assertion for the given {@link MultipleNodePcesResults}
     */
    @NonNull
    public static MultipleNodePcesResultsAssert assertThat(@Nullable final MultipleNodePcesResults actual) {
        return MultipleNodePcesResultsAssert.assertThat(actual);
    }
}
