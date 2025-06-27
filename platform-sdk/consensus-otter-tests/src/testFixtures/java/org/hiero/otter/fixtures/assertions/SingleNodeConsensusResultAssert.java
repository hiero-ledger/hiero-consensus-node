// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Assertions for {@link SingleNodeConsensusResult}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
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
    public SingleNodeConsensusResultAssert hasAdvancedSinceRound(final long expected) {
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
    public SingleNodeConsensusResultAssert hasBirthRoundSplit(@NonNull final Instant splitTime, final long splitRound) {
        isNotNull();
        for (final ConsensusRound round : actual().consensusRounds()) {
            for (final PlatformEvent event : round.getConsensusEvents()) {
                if (event.getTimeCreated().isAfter(splitTime)) {
                    Assertions.assertThat(event.getBirthRound())
                            .withFailMessage(
                                    "Expected events with creation time less than or equal to %s to "
                                            + "have a birth round less than or equal to <%s>, but creation time "
                                            + "was %s and birth round was <%s>",
                                    splitTime, splitRound, event.getTimeCreated(), event.getBirthRound())
                            .isGreaterThan(splitRound);
                } else {
                    Assertions.assertThat(event.getBirthRound())
                            .withFailMessage(
                                    "Expected events with creation time greater than %s to "
                                            + "have a birth round greater than <%s>, but creation time "
                                            + "was %s and birth round was <%s>",
                                    splitTime, splitRound, event.getTimeCreated(), event.getBirthRound())
                            .isLessThanOrEqualTo(splitRound);
                }
            }
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
    public SingleNodeConsensusResultAssert hasConsistentRounds() {
        isNotNull();
        ConsensusRoundValidator.validate(actual.consensusRounds());
        return this;
    }
}
