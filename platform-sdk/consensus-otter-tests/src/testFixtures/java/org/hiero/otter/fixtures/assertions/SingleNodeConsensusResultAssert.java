// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Assertions for {@link SingleNodeConsensusResult}.
 */
@SuppressWarnings("UnusedReturnValue")
public class SingleNodeConsensusResultAssert
        extends AbstractAssert<SingleNodeConsensusResultAssert, SingleNodeConsensusResult> {

    /**
     * Creates a new instance of {@link SingleNodeConsensusResultAssert}.
     *
     * @param actual the actual {@link SingleNodeConsensusResult} to assert
     */
    public SingleNodeConsensusResultAssert(@Nullable final SingleNodeConsensusResult actual) {
        super(actual, SingleNodeConsensusResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param actual the {@link SingleNodeConsensusResult} to assert
     * @return an assertion for the given {@link SingleNodeConsensusResult}
     */
    @NonNull
    public static SingleNodeConsensusResultAssert assertThat(@Nullable final SingleNodeConsensusResult actual) {
        return new SingleNodeConsensusResultAssert(actual);
    }

    /**
     * Verifies that the last round created is equal to the expected value.
     *
     * @param expected the expected last round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasLastRoundNum(final long expected) {
        isNotNull();
        if (actual.lastRoundNum() != expected) {
            failWithMessage(
                    "Expected last round of node %s to be <%d> but was <%d>",
                    actual.nodeId(), expected, actual.lastRoundNum());
        }
        return this;
    }

    /**
     * Verifies that the last round created is larger than the expected value, in other words, that the consensus
     * algorithm has proceeded since the given round.
     *
     * @param expected the round to compare with
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasAdvancedSince(final long expected) {
        isNotNull();
        if (actual.lastRoundNum() <= expected) {
            failWithMessage(
                    "Expected last round of node %s to be larger than <%d> but was <%d>",
                    actual.nodeId(), expected, actual.lastRoundNum());
        }
        return this;
    }

    /**
     * Verifies that the created consensus rounds are equal to the expected rounds.
     *
     * @param expected the expected rounds
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasRounds(@NonNull final List<ConsensusRound> expected) {
        isNotNull();
        Assertions.assertThat(actual.consensusRounds())
                .withFailMessage(
                        "Expected consensus rounds of node %s to be <%s> but was <%s>",
                        actual.nodeId(), expected, actual.consensusRounds())
                .containsExactlyElementsOf(expected);
        return this;
    }
}
