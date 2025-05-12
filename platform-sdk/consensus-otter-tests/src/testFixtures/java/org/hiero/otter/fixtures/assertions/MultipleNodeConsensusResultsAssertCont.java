// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
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
@SuppressWarnings("UnusedReturnValue")
public class MultipleNodeConsensusResultsAssertCont
        extends AbstractAssert<MultipleNodeConsensusResultsAssertCont, MultipleNodeConsensusResults>
        implements ContinuousAssertion {

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param multipleNodeConsensusResults the actual {@link MultipleNodeConsensusResults} to assert
     */
    MultipleNodeConsensusResultsAssertCont(MultipleNodeConsensusResults multipleNodeConsensusResults) {
        super(multipleNodeConsensusResults, MultipleNodeConsensusResultsAssertCont.class);
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
    public MultipleNodeConsensusResultsAssertCont hasEqualRounds() {
        isNotNull();

        final ConsensusRoundSubscriber subscriber = new ConsensusRoundSubscriber() {

            final Map<Long, RoundResult> referenceRounds = new ConcurrentHashMap<>();

            @Override
            public SubscriberAction onConsensusRounds(@NonNull NodeId nodeId, @NonNull List<ConsensusRound> rounds) {
                for (final ConsensusRound round : rounds) {
                    final RoundResult reference = referenceRounds.computeIfAbsent(round.getRoundNum(), key -> new RoundResult(nodeId, round));
                    if (! round.equals(reference.round())) {
                        throw new AssertionError("Expected rounds to be equal, but round %d differs. Node %s produced %s, while node %s produced %s".formatted(
                                round.getRoundNum(),
                                nodeId,
                                round,
                                reference.nodeId(),
                                reference.round()));
                    }
                }
                return stopped.get() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
            }
        };

        actual.subscribe(subscriber);

        return this;
    }

    private record RoundResult(@NonNull NodeId nodeId, @NonNull ConsensusRound round) {}
}
