// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.ConsensusRoundSubscriber.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.ConsensusRoundSubscriber.SubscriberAction.UNSUBSCRIBE;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;

/**
 * Continues assertions for {@link MultipleNodeConsensusResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeConsensusResultsContinuousAssert
        extends AbstractAssert<MultipleNodeConsensusResultsContinuousAssert, MultipleNodeConsensusResults>
        implements ContinuousAssertion {

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param multipleNodeConsensusResults the actual {@link MultipleNodeConsensusResults} to assert
     */
    public MultipleNodeConsensusResultsContinuousAssert(
            @NonNull final MultipleNodeConsensusResults multipleNodeConsensusResults) {
        super(multipleNodeConsensusResults, MultipleNodeConsensusResultsContinuousAssert.class);
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
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        stopped.set(true);
    }

    /**
     * Verifies that all nodes produce the same rounds. This check only compares the rounds produced by the nodes, i.e.,
     * if a node produces no rounds or is significantly behind the others, this check will NOT fail.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsContinuousAssert haveEqualRounds() {
        isNotNull();

        final ConsensusRoundSubscriber subscriber = new ConsensusRoundSubscriber() {

            final Map<Long, RoundResult> referenceRounds = new ConcurrentHashMap<>();

            @Override
            public SubscriberAction onConsensusRounds(
                    @NonNull final NodeId nodeId, final @NonNull List<ConsensusRound> rounds) {
                if (stopped.get()) {
                    return UNSUBSCRIBE;
                }
                for (final ConsensusRound round : rounds) {
                    final RoundResult reference =
                            referenceRounds.computeIfAbsent(round.getRoundNum(), key -> new RoundResult(nodeId, round));
                    if (!nodeId.equals(reference.nodeId) && !round.equals(reference.round())) {
                        throw new AssertionError(
                                "Expected rounds to be equal, but round %d differs. Node %s produced %s, while node %s produced %s"
                                        .formatted(
                                                round.getRoundNum(),
                                                nodeId,
                                                round,
                                                reference.nodeId(),
                                                reference.round()));
                    }
                }
                return CONTINUE;
            }
        };

        actual.subscribe(subscriber);

        return this;
    }

    private record RoundResult(@NonNull NodeId nodeId, @NonNull ConsensusRound round) {}
}
