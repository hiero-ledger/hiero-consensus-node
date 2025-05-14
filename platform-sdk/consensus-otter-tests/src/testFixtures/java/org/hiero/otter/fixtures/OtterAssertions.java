// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.Assertions;
import org.hiero.otter.fixtures.assertions.MultipleNodeConsensusResultsAssert;
import org.hiero.otter.fixtures.assertions.MultipleNodeStatusProgressionAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeConsensusResultAssert;
import org.hiero.otter.fixtures.assertions.SingleNodeStatusProgressionAssert;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeStatusProgression;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * This class contains all {@code assertThat()} methods for test results of the Otter framework.
 */
public class OtterAssertions extends Assertions {

    private OtterAssertions() {}

    /**
     * Creates an assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param result the {@link SingleNodeConsensusResult} to assert
     * @return an assertion for the given {@link SingleNodeConsensusResult}
     */
    @NonNull
    public static SingleNodeConsensusResultAssert assertThat(@Nullable final SingleNodeConsensusResult result) {
        return SingleNodeConsensusResultAssert.assertThat(result);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param result the {@link MultipleNodeConsensusResults} to assert
     * @return an assertion for the given {@link MultipleNodeConsensusResults}
     */
    @NonNull
    public static MultipleNodeConsensusResultsAssert assertThat(@Nullable final MultipleNodeConsensusResults result) {
        return MultipleNodeConsensusResultsAssert.assertThat(result);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeStatusProgression}.
     *
     * @param statusProgression the {@link SingleNodeStatusProgression} to assert
     * @return an assertion for the given {@link SingleNodeStatusProgression}
     */
    @NonNull
    public static SingleNodeStatusProgressionAssert assertThat(
            @Nullable final SingleNodeStatusProgression statusProgression) {
        return SingleNodeStatusProgressionAssert.assertThat(statusProgression);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeStatusProgression}.
     *
     * @param statusProgression the {@link MultipleNodeStatusProgression} to assert
     * @return an assertion for the given {@link MultipleNodeStatusProgression}
     */
    @NonNull
    public static MultipleNodeStatusProgressionAssert assertThat(
            @Nullable final MultipleNodeStatusProgression statusProgression) {
        return MultipleNodeStatusProgressionAssert.assertThat(statusProgression);
    }
}
