// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.event.PlatformEvent;
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
                        final NodeId protoNodeId =
                                NodeId.newBuilder().id(nodeId.id()).build();
                        if (!suppressedNodeIds.contains(protoNodeId)) {
                            for (final ConsensusRound round : rounds) {
                                final RoundFromNode reference = referenceRounds.computeIfAbsent(
                                        round.getRoundNum(), key -> new RoundFromNode(protoNodeId, round));
                                if (!protoNodeId.equals(reference.nodeId) && !round.equals(reference.round())) {
                                    failWithMessage(summarizeDifferences(
                                            reference.nodeId,
                                            protoNodeId,
                                            round.getRoundNum(),
                                            reference.round(),
                                            round));
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
            final PlatformEvent event1 = round1.getConsensusEvents().get(i);
            final PlatformEvent event2 = round2.getConsensusEvents().get(i);
            if (!event1.equals(event2)) {
                sb.append("Event ").append(i).append(" differs:\n");
                sb.append("Node ").append(node1.id()).append(" produced\n").append(event1);
                sb.append("Node ").append(node2.id()).append(" produced\n").append(event2);
            }
        }
        return sb.toString();
    }

    private record RoundFromNode(@NonNull NodeId nodeId, @NonNull ConsensusRound round) {}
}
