// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.RoundInternalEqualityValidation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Continuous assertions for {@link MultipleNodeConsensusResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeConsensusResultsContinuousAssert
        extends AbstractMultipleNodeContinuousAssertion<
                MultipleNodeConsensusResultsContinuousAssert, MultipleNodeConsensusResults> {

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param actual the actual {@link MultipleNodeConsensusResults} to assert
     */
    public MultipleNodeConsensusResultsContinuousAssert(@Nullable final MultipleNodeConsensusResults actual) {
        super(actual, MultipleNodeConsensusResultsContinuousAssert.class);
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
        return new MultipleNodeConsensusResultsContinuousAssert(actual);
    }

    /**
     * Verifies that the created consensus rounds are consistent.
     *
     * <p>This includes checking if the ancient thresholds are increasing and the timestamps of
     * events are strictly increasing.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsContinuousAssert haveConsistentRounds() {
        isNotNull();

        final ConsensusRoundSubscriber subscriber = new ConsensusRoundSubscriber() {

            // For some validations to function properly, we have to prepend the last round
            private ConsensusRound lastRound = null;

            @Override
            public SubscriberAction onConsensusRounds(
                    @NonNull final NodeId nodeId, final @NonNull List<ConsensusRound> rounds) {
                return switch (state) {
                    case ACTIVE -> {
                        if (!suppressedNodeIds.contains(nodeId)) {
                            final List<ConsensusRound> includingLast = Stream.concat(
                                            Stream.ofNullable(lastRound), rounds.stream())
                                    .toList();
                            ConsensusRoundValidator.validate(includingLast);
                            lastRound = rounds.getLast();
                        }
                        yield CONTINUE;
                    }
                    case PAUSED -> CONTINUE;
                    case DESTROYED -> UNSUBSCRIBE;
                };
            }
        };

        actual.subscribe(subscriber);

        return this;
    }

    /**
     * Verifies that all nodes produce equal rounds as they are produced. This check only compares the rounds produced
     * by the nodes, i.e., if a node produces no rounds or is significantly behind the others, this check will NOT
     * fail.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsContinuousAssert haveEqualRounds() {
        isNotNull();

        final ConsensusRoundSubscriber subscriber = new ConsensusRoundSubscriber() {

            final Map<Long, RoundFromNode> referenceRounds = new ConcurrentHashMap<>();

            @Override
            public SubscriberAction onConsensusRounds(
                    @NonNull final NodeId nodeId, final @NonNull List<ConsensusRound> rounds) {
                return switch (state) {
                    case ACTIVE -> {
                        if (!suppressedNodeIds.contains(nodeId)) {
                            for (final ConsensusRound round : rounds) {
                                final RoundFromNode reference = referenceRounds.computeIfAbsent(
                                        round.getRoundNum(), key -> new RoundFromNode(nodeId, round));
                                if (!nodeId.equals(reference.nodeId)) {
                                    RoundInternalEqualityValidation.INSTANCE.validate(reference.round(), round);
                                }
                            }
                        }
                        yield CONTINUE;
                    }
                    case PAUSED -> CONTINUE;
                    case DESTROYED -> UNSUBSCRIBE;
                };
            }
        };

        actual.subscribe(subscriber);

        return this;
    }

    private record RoundFromNode(@NonNull NodeId nodeId, @NonNull ConsensusRound round) {}
}
