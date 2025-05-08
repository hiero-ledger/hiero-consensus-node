// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
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
        if (actual.results().isEmpty()) {
            throw new IllegalArgumentException("Trying to assert empty results. This is unlikely to be intended.");
        }
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

        // find longest and shortest list
        final SingleNodeConsensusResult longestResult = actual.results().stream()
                .max(Comparator.comparing(r -> r.consensusRounds().size()))
                .orElseThrow();
        final int shortestSize = actual.results().stream()
                .map(SingleNodeConsensusResult::consensusRounds)
                .mapToInt(List::size)
                .min()
                .orElse(0);

        // Check if difference is within bounds
        final int longestSize = longestResult.consensusRounds().size();
        final double actualDifference = 100.0 * (longestSize - shortestSize) / longestSize;
        if (actualDifference > expectedDifference.value) {
            failWithMessage(
                    "Expected the difference between the fastest and the slowest node not to be greater than %s, but was %2.f%%",
                    expectedDifference, actualDifference);
        }

        // Check that all nodes produced the same consensus rounds as are in the longest list
        for (final SingleNodeConsensusResult currentNodeResult : actual.results()) {
            if (currentNodeResult.nodeId().equals(longestResult.nodeId())) {
                continue;
            }
            final int size = currentNodeResult.consensusRounds().size();
            final List<ConsensusRound> expectedSublist =
                    longestResult.consensusRounds().subList(0, size);
            OtterAssertions.assertThat(currentNodeResult)
                    .withFailMessage(
                            "Expected node %s to have the same consensus rounds as node %s, but the former had %s while the later had %s",
                            currentNodeResult.nodeId(),
                            longestResult.nodeId(),
                            currentNodeResult.consensusRounds(),
                            expectedSublist)
                    .hasRounds(expectedSublist);
        }

        return this;
    }
}
