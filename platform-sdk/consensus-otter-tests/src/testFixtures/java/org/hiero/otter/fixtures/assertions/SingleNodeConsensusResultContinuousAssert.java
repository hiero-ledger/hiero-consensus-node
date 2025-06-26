// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Continuous assertions for {@link SingleNodeConsensusResult}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodeConsensusResultContinuousAssert
        extends AbstractContinuousAssertion<SingleNodeConsensusResultContinuousAssert, SingleNodeConsensusResult> {

    /**
     * Creates a continuous assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param actual the actual {@link SingleNodeConsensusResult} to assert
     */
    public SingleNodeConsensusResultContinuousAssert(@Nullable final SingleNodeConsensusResult actual) {
        super(actual, SingleNodeConsensusResultContinuousAssert.class);
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
        return new SingleNodeConsensusResultContinuousAssert(actual);
    }
}
