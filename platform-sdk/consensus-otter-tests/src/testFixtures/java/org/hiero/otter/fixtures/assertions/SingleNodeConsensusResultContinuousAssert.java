// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Continuous assertions for {@link SingleNodeConsensusResult}.
 *
 * <p>Please note: If two continuous assertions fail roughly at the same time, it is non-deterministic which one
 * will report the failure first. This is even true when running a test in the Turtle environment.
 * If deterministic behavior is required, please use regular assertions instead of continuous assertions.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodeConsensusResultContinuousAssert
        extends AbstractContinuousAssertion<SingleNodeConsensusResultContinuousAssert, SingleNodeConsensusResult> {

    /**
     * Creates a continuous assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param actual the actual {@link SingleNodeConsensusResult} to assert
     */
    public SingleNodeConsensusResultContinuousAssert(@Nullable final SingleNodeConsensusResult actual) {
        super(actual, SingleNodeConsensusResultContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param actual the {@link SingleNodeConsensusResult} to assert
     * @return a continuous assertion for the given {@link SingleNodeConsensusResult}
     */
    @NonNull
    public static SingleNodeConsensusResultContinuousAssert assertContinuouslyThat(
            @Nullable final SingleNodeConsensusResult actual) {
        return new SingleNodeConsensusResultContinuousAssert(actual);
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
    public SingleNodeConsensusResultContinuousAssert hasConsistentRounds() {
        isNotNull();

        final ConsensusRoundSubscriber subscriber = new ConsensusRoundSubscriber() {

            // For some validations to function properly, we have to prepend the last round
            private ConsensusRound lastRound = null;

            @Override
            public SubscriberAction onConsensusRounds(
                    @NonNull final NodeId nodeId, final @NonNull List<ConsensusRound> rounds) {
                return switch (state) {
                    case ACTIVE -> {
                        final List<ConsensusRound> includingLast = Stream.concat(
                                        Stream.ofNullable(lastRound), rounds.stream())
                                .toList();
                        ConsensusRoundValidator.validate(includingLast);
                        if (!rounds.isEmpty()) {
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
}
