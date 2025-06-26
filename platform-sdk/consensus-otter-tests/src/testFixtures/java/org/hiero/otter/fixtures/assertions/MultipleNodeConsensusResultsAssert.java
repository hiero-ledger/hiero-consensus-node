// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static java.util.Comparator.comparingInt;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Assertions for {@link MultipleNodeConsensusResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeConsensusResultsAssert
        extends AbstractAssert<MultipleNodeConsensusResultsAssert, MultipleNodeConsensusResults> {

    /**
     * Creates a new instance of {@link MultipleNodeConsensusResultsAssert}
     *
     * @param actual the actual {@link MultipleNodeConsensusResults} to assert
     */
    public MultipleNodeConsensusResultsAssert(@Nullable final MultipleNodeConsensusResults actual) {
        super(actual, MultipleNodeConsensusResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param actual the {@link MultipleNodeConsensusResults} to assert
     * @return an assertion for the given {@link MultipleNodeConsensusResults}
     */
    @NonNull
    public static MultipleNodeConsensusResultsAssert assertThat(@Nullable final MultipleNodeConsensusResults actual) {
        return new MultipleNodeConsensusResultsAssert(actual);
    }

    /**
     * Verifies that all nodes reached consensus on the same, provided round. Naturally, this check only makes sense
     * while the nodes are halted.
     *
     * @param expected the expected last round
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert haveLastRoundNum(final long expected) {
        isNotNull();

        for (final SingleNodeConsensusResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasLastRoundNum(expected);
        }

        return this;
    }

    /**
     * Verifies that all nodes have advanced by at least one round since the provided round.
     *
     * @param expected the round number to compare with
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert haveAdvancedSinceRound(final long expected) {
        isNotNull();

        for (final SingleNodeConsensusResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasAdvancedSinceRound(expected);
        }

        return this;
    }

    /**
     * Identifies the rounds which have reached consensus on all nodes and verifies that they are equal. If a node has
     * not produced any rounds, this assertion will always pass.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert roundsInCommonAreEqual() {
        isNotNull();

        // create list of actual rounds
        final List<NodeRoundsResult> actualNodeRoundsResult = actual.results().stream()
                .map(nodeResult -> new NodeRoundsResult(nodeResult.nodeId(), nodeResult.consensusRounds()))
                .toList();

        // find list
        final Optional<NodeRoundsResult> optionalLongestNodeRoundsResult =
                actualNodeRoundsResult.stream().max(comparingInt(NodeRoundsResult::size));
        if (optionalLongestNodeRoundsResult.isEmpty()) {
            // no consensus rounds collected
            return this;
        }
        final NodeRoundsResult longestNodeRoundsResult = optionalLongestNodeRoundsResult.get();

        for (final NodeRoundsResult roundsFromNodeToAssert : actualNodeRoundsResult) {
            if (roundsFromNodeToAssert.nodeId().equals(longestNodeRoundsResult.nodeId())) {
                continue;
            }

            // Get the same rounds from this node as create by the node with the most rounds and compare them
            final List<ConsensusRound> roundsToAssert = roundsFromNodeToAssert.rounds();
            final List<ConsensusRound> expectedRounds =
                    longestNodeRoundsResult.rounds().subList(0, roundsToAssert.size());
            Assertions.assertThat(roundsToAssert)
                    .withFailMessage(
                            "Expected node %s to have the same consensus rounds as node %s, but the former had %s while the later had %s",
                            longestNodeRoundsResult.nodeId(),
                            roundsFromNodeToAssert.nodeId(),
                            roundsToAssert,
                            longestNodeRoundsResult.rounds())
                    .containsExactlyElementsOf(expectedRounds);
        }

        return this;
    }

    /**
     * Verifies that the difference in the number of rounds produced by the fastest and the slowest node is less than or
     * equal to the given percentage.
     *
     * @param expectedDifference the percentage of difference in number of consensus rounds that is allowed between the
     *                           fastest and the slowest node
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert hasMaxDifferenceInLastRoundNum(
            @NonNull final Percentage expectedDifference) {
        isNotNull();

        // create list of current rounds
        final List<NodeRoundsResult> currentRoundResults = actual.results().stream()
                .map(nodeResult -> new NodeRoundsResult(nodeResult.nodeId(), nodeResult.consensusRounds()))
                .toList();

        // find longest and shortest list
        final Optional<NodeRoundsResult> optionalLongestResult =
                currentRoundResults.stream().max(comparingInt(NodeRoundsResult::size));
        if (optionalLongestResult.isEmpty()) {
            // no consensus rounds collected
            return this;
        }
        final NodeRoundsResult longestResult = optionalLongestResult.get();
        final int longestSize = longestResult.size();
        final int shortestSize = currentRoundResults.stream()
                .min(comparingInt(NodeRoundsResult::size))
                .orElseThrow()
                .size();

        // Check if difference is within bounds
        final double actualDifference = 100.0 * (longestSize - shortestSize) / longestSize;
        if (actualDifference > expectedDifference.value) {
            failWithMessage(
                    "Expected the difference between the fastest and the slowest node not to be greater than %s, but was %2.f%%",
                    expectedDifference, actualDifference);
        }

        return this;
    }

    private record NodeRoundsResult(@NonNull NodeId nodeId, @NonNull List<ConsensusRound> rounds) {
        private int size() {
            return rounds.size();
        }
    }
}
