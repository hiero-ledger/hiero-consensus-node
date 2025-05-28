// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static java.util.Comparator.comparingInt;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
@SuppressWarnings("UnusedReturnValue")
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

    @NonNull
    private MultipleNodeConsensusResultsAssert checkAll(
            @NonNull final Consumer<SingleNodeConsensusResultAssert> check) {
        isNotNull();
        for (final SingleNodeConsensusResult result : actual.results()) {
            check.accept(OtterAssertions.assertThat(result));
        }
        return this;
    }

    /**
     * Verifies that all nodes reached consensus on the same, provided round.
     * Naturally, this check only makes sense while the nodes are halted.
     *
     * @param expected the expected last round
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert hasLastRoundNum(final long expected) {
        return checkAll(singleNodeResult -> singleNodeResult.hasLastRoundNum(expected));
    }

    /**
     * Verifies that all nodes have advanced by at least one round since the provided round.
     *
     * @param expected the round number to compare with
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert hasAdvancedSince(final long expected) {
        return checkAll(singleNodeResult -> singleNodeResult.hasAdvancedSince(expected));
    }

    /**
     * Verifies that all nodes have produced the same rounds acknowledging that nodes may have progressed
     * differently (up to a given maximum).
     *
     * @param expectedDifference the maximum percentage of rounds that some nodes may have progressed farther than others
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert hasEqualRoundsIgnoringLast(@NonNull final Percentage expectedDifference) {
        isNotNull();

        // create list of current rounds
        final List<RoundListResult> currentRoundResults = actual.results().stream()
                .map(nodeResult -> new RoundListResult(nodeResult.nodeId(), nodeResult.consensusRounds()))
                .toList();

        // find longest and shortest list
        final Optional<RoundListResult> optionalLongestResult =
                currentRoundResults.stream().max(comparingInt(RoundListResult::size));
        if (optionalLongestResult.isEmpty()) {
            // no consensus rounds collected
            return this;
        }
        final RoundListResult longestResult = optionalLongestResult.get();
        final int longestSize = longestResult.size();
        final int shortestSize = currentRoundResults.stream()
                .min(comparingInt(RoundListResult::size))
                .orElseThrow()
                .size();

        // Check if difference is within bounds
        final double actualDifference = 100.0 * (longestSize - shortestSize) / longestSize;
        if (actualDifference > expectedDifference.value) {
            failWithMessage(
                    "Expected the difference between the fastest and the slowest node not to be greater than %s, but was %2.f%%",
                    expectedDifference, actualDifference);
        }

        // Check that all nodes produced the same consensus rounds as are in the longest list
        for (final RoundListResult currentNodeResult : currentRoundResults) {
            if (currentNodeResult.nodeId().equals(longestResult.nodeId())) {
                continue;
            }
            final int size = currentNodeResult.size();
            final List<ConsensusRound> expectedSublist = longestResult.rounds().subList(0, size);
            Assertions.assertThat(currentNodeResult.rounds())
                    .withFailMessage(
                            "Expected node %s to have the same consensus rounds as node %s, but the former had %s while the later had %s",
                            currentNodeResult.nodeId(),
                            longestResult.nodeId(),
                            currentNodeResult.rounds(),
                            expectedSublist)
                    .containsExactlyElementsOf(expectedSublist);
        }

        return this;
    }

    private record RoundListResult(@NonNull NodeId nodeId, @NonNull List<ConsensusRound> rounds) {
        private int size() {
            return rounds.size();
        }
    }
}
