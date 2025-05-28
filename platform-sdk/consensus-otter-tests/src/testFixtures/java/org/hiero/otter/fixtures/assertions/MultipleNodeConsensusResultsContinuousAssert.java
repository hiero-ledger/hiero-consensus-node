// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.ConsensusRoundSubscriber.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.ConsensusRoundSubscriber.SubscriberAction.UNSUBSCRIBE;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;

/**
 * Continuous assertions for {@link MultipleNodeConsensusResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeConsensusResultsContinuousAssert
        extends AbstractAssert<MultipleNodeConsensusResultsContinuousAssert, MultipleNodeConsensusResults>
        implements ContinuousAssertion {

    private enum State {
        ACTIVE,
        PAUSED,
        DESTROYED
    }

    private final Set<NodeId> suppressedNodeIds = ConcurrentHashMap.newKeySet();
    private volatile State state = State.ACTIVE;

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeConsensusResults}.
     *
     * @param multipleNodeConsensusResults the actual {@link MultipleNodeConsensusResults} to assert
     */
    public MultipleNodeConsensusResultsContinuousAssert(
            @Nullable final MultipleNodeConsensusResults multipleNodeConsensusResults) {
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
    public void pause() {
        state = State.PAUSED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume() {
        state = State.ACTIVE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        state = State.DESTROYED;
    }

    /**
     * Suppresses the given node from the assertions.
     *
     * @param nodeId the id of the node to suppress
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsContinuousAssert startSuppressingNode(@NonNull final NodeId nodeId) {
        suppressedNodeIds.add(nodeId);
        return this;
    }

    /**
     * Suppresses the given node from the assertions.
     *
     * @param node the {@link Node} to suppress
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsContinuousAssert startSuppressingNode(@NonNull final Node node) {
        return startSuppressingNode(node.getSelfId());
    }

    /**
     * Stops suppressing the given node from the assertions.
     *
     * @param nodeId the id of the node
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsContinuousAssert stopSuppressingNode(@NonNull final NodeId nodeId) {
        suppressedNodeIds.remove(nodeId);
        return this;
    }

    /**
     * Stops suppressing the given node from the assertions.
     *
     * @param node the {@link Node}
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeConsensusResultsContinuousAssert stopSuppressingNode(@NonNull final Node node) {
        return stopSuppressingNode(node.getSelfId());
    }

    /**
     * Verifies that all nodes produce equal rounds as they are produces. This check only compares the rounds produced by the nodes, i.e.,
     * if a node produces no rounds or is significantly behind the others, this check will NOT fail.
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
                                if (!nodeId.equals(reference.nodeId) && !round.equals(reference.round())) {
                                    failWithMessage(summarizeDifferences(
                                            reference.nodeId, nodeId, round.getRoundNum(), reference.round(), round));
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

    private static String summarizeDifferences(
            @NonNull final NodeId node1,
            @NonNull final NodeId node2,
            final long roundNum,
            @NonNull final ConsensusRound round1,
            @NonNull final ConsensusRound round2) {
        if (round1.getEventCount() != round2.getEventCount()) {
            return "Expected rounds to be equal, but round %d of node %s has %d events, while the same round of node %s has %d events."
                    .formatted(roundNum, node1, round1.getEventCount(), node2, round2.getEventCount());
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("Expected rounds to be equal, but round ")
                .append(roundNum)
                .append(" has the following differences:\n");
        for (int i = 0; i < round1.getEventCount(); i++) {
            final var event1 = round1.getConsensusEvents().get(i);
            final var event2 = round2.getConsensusEvents().get(i);
            if (!event1.equals(event2)) {
                sb.append("Event ").append(i).append(" differs:\n");
                sb.append("Node ").append(node1).append(" produced\n").append(event1);
                sb.append("Node ").append(node2).append(" produced\n").append(event2);
            }
        }
        return sb.toString();
    }

    private record RoundFromNode(@NonNull NodeId nodeId, @NonNull ConsensusRound round) {}
}
