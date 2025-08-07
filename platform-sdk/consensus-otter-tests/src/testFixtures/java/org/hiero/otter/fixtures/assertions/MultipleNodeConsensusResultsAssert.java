// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static java.util.Comparator.comparingInt;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
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
    public MultipleNodeConsensusResultsAssert haveEqualCommonRounds() {
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
            ConsensusRoundValidator.validate(roundsToAssert, expectedRounds);
        }

        return this;
    }

    /**
     * Verifies that events with a creation time prior to and including the given {@code splitTime} have a birth round
     * equal to or less than the {@code splitRound}, and all events with a creation time after the {@code splitTime}
     * have a birth ground greater than {@code splitRound}.
     *
     * @param splitTime  all events with a creation time before and including this time should have a birth round equal
     *                   to or less that the {@code splitRound}
     * @param splitRound the maximum birth round for events created before and up to the {@code splitTime}
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsAssert haveBirthRoundSplit(
            @NonNull final Instant splitTime, final long splitRound) {
        isNotNull();
        for (final SingleNodeConsensusResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasBirthRoundSplit(splitTime, splitRound);
        }
        return this;
    }

    /**
     * Verifies that the created consensus rounds are consistent.
     *
     * <p>This includes checking if the ancient thresholds are increasing and the timestamps of
     * events are strictly increasing.
     *
     * @return this assertion object for method chaining
     */
    public MultipleNodeConsensusResultsAssert haveConsistentRounds() {
        isNotNull();
        for (final SingleNodeConsensusResult result : actual.results()) {
            OtterAssertions.assertThat(result).hasConsistentRounds();
        }
        return this;
    }

    private record NodeRoundsResult(@NonNull NodeId nodeId, @NonNull List<ConsensusRound> rounds) {
        private int size() {
            return rounds.size();
        }
    }
}
