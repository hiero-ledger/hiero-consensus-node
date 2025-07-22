// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.Assertions;
import org.hiero.otter.fixtures.assertions.MultipleNodeConsensusResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodeConsensusResultsContinuousAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodeLogResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodeLogResultsContinuousAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodePcesResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodePlatformStatusResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodePlatformStatusResultsContinuousAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodeReconnectResultsAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeConsensusResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeConsensusResultContinuousAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeLogResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeLogResultContinuousAssert;
import org.hiero.otter.fixtures.assertions.SingleNodePcesResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodePlatformStatusResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodePlatformStatusResultContinuousAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeReconnectResultsAssert;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

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
     * Creates a continuous assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param actual the {@link SingleNodeConsensusResult} to assert
     * @return a continuous assertion for the given {@link SingleNodeConsensusResult}
     */
    @NonNull
    public static SingleNodeConsensusResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodeConsensusResult actual) {
        return SingleNodeConsensusResultContinuousAssert.assertContinuouslyThat(actual);
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
     * Creates a continuous assertion for the given {@link SingleNodeLogResult}.
     *
     * @param actual the {@link SingleNodeLogResult} to assert
     * @return an assertion for the given {@link SingleNodeLogResult}
     */
    @NonNull
    public static SingleNodeLogResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodeLogResult actual) {
        return SingleNodeLogResultContinuousAssert.assertContinuouslyThat(actual);
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
     * Creates a continuous assertion for the given {@link MultipleNodeLogResults}.
     *
     * @param actual the {@link MultipleNodeLogResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodeLogResults}
     */
    @NonNull
    public static MultipleNodeLogResultsContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodeLogResults actual) {
        return MultipleNodeLogResultsContinuousAssert.assertContinuouslyThat(actual);
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
     * Creates a continuous assertion for the given {@link SingleNodePlatformStatusResults}.
     *
     * @param actual the {@link SingleNodePlatformStatusResults} to assert
     * @return a continuous assertion for the given {@link SingleNodePlatformStatusResults}
     */
    @NonNull
    public static SingleNodePlatformStatusResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodePlatformStatusResults actual) {
        return SingleNodePlatformStatusResultContinuousAssert.assertContinuouslyThat(actual);
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
     * Creates a continuous assertion for the given {@link MultipleNodePlatformStatusResults}.
     *
     * @param actual the {@link MultipleNodePlatformStatusResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodePlatformStatusResults}
     */
    @NonNull
    public static MultipleNodePlatformStatusResultsContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodePlatformStatusResults actual) {
        return MultipleNodePlatformStatusResultsContinuousAssert.assertContinuouslyThat(actual);
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

    /**
     * Creates an assertion for the given {@link SingleNodeReconnectResult}.
     *
     * @param actual the {@link SingleNodeReconnectResult} to assert
     * @return an assertion for the given {@link SingleNodeReconnectResult}
     */
    @NonNull
    public static SingleNodeReconnectResultsAssert assertThat(@Nullable final SingleNodeReconnectResult actual) {
        return SingleNodeReconnectResultsAssert.assertThat(actual);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeReconnectResult}.
     *
     * @param actual the {@link MultipleNodeReconnectResults} to assert
     * @return an assertion for the given {@link MultipleNodeReconnectResults}
     */
    @NonNull
    public static MultipleNodeReconnectResultsAssert assertThat(@Nullable final MultipleNodeReconnectResults actual) {
        return MultipleNodeReconnectResultsAssert.assertThat(actual);
    }
}
