// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.RoundInternalEqualityValidation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;

/**
 * Continuous assertions for {@link MultipleNodeConsensusResults}.
 *
 * <p>Please note: If two continuous assertions fail roughly at the same time, it is non-deterministic which one
 * will report the failure first. This is even true when running a test in the Turtle environment. If deterministic
 * behavior is required, please use regular assertions instead of continuous assertions.
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
        final Map<NodeId, ConsensusRound> lastRoundByNodeId = new ConcurrentHashMap<>();
        return checkContinuously((nodeId, rounds) -> {
            // For some validations to function properly, we have to prepend the last round
            final List<ConsensusRound> includingLast = Stream.concat(
                            Stream.ofNullable(lastRoundByNodeId.get(nodeId)), rounds.stream())
                    .toList();
            ConsensusRoundValidator.validate(includingLast);
            if (!rounds.isEmpty()) {
                lastRoundByNodeId.put(nodeId, rounds.getLast());
            }
        });
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
        final Map<Long, RoundFromNode> referenceRounds = new ConcurrentHashMap<>();
        return checkContinuously((nodeId, rounds) -> {
            for (final ConsensusRound round : rounds) {
                final RoundFromNode reference =
                        referenceRounds.computeIfAbsent(round.getRoundNum(), key -> new RoundFromNode(nodeId, round));
                if (!nodeId.equals(reference.nodeId)) {
                    RoundInternalEqualityValidation.INSTANCE.validate(reference.round(), round);
                }
            }
        });
    }

    private record RoundFromNode(@NonNull NodeId nodeId, @NonNull ConsensusRound round) {}

    private MultipleNodeConsensusResultsContinuousAssert checkContinuously(
            @NonNull final BiConsumer<NodeId, List<ConsensusRound>> check) {
        isNotNull();

        final ConsensusRoundSubscriber subscriber = (nodeId, rounds) -> switch (state) {
            case ACTIVE -> {
                if (!suppressedNodeIds.contains(nodeId)) {
                    check.accept(nodeId, rounds);
                }
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
