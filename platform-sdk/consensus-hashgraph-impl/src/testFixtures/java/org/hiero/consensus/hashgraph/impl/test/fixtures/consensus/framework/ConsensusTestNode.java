// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusOutput;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.TestIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.EventEmitter;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/** A type which is responsible for managing a node in a consensus test */
public class ConsensusTestNode {
    /** The event emitter that produces events. */
    private final EventEmitter eventEmitter;

    /** The instance to apply events to. */
    private final TestIntake intake;

    private final Random random;

    /**
     * Creates a new instance.
     *
     * @param eventEmitter the emitter of events
     * @param intake       the instance to apply events to
     */
    public ConsensusTestNode(@NonNull final EventEmitter eventEmitter, @NonNull final TestIntake intake) {
        this.eventEmitter = eventEmitter;
        this.intake = intake;
        this.random = new Random();
    }

    /**
     * Creates a new instance with a freshly seeded {@link EventEmitter}.
     *
     * @param configuration   the configuration to use for the intake and emitter
     * @param metrics         the metrics to use for the intake and emitter
     * @param time            the time to use for the intake and emitter
     * @param eventEmitter    the emitter of events
     */
    public static @NonNull ConsensusTestNode genesisContext(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final EventEmitter eventEmitter) {
        return new ConsensusTestNode(
                eventEmitter,
                new TestIntake(
                        configuration,
                        metrics,
                        time,
                        eventEmitter.getGraphGenerator().getRoster()));
    }

    /** Simulates a restart on a node */
    public void restart() {
        // clear all generators
        eventEmitter.reset();
        final ConsensusSnapshot snapshot = Objects.requireNonNull(
                        getOutput().getConsensusRounds().peekLast())
                .getSnapshot();
        intake.reset();
        intake.loadSnapshot(snapshot);
    }

    /**
     * Simulates removing a node from the network at restart
     * @param nodeId the node to remove
     */
    public void removeNode(@NonNull final NodeId nodeId) {
        eventEmitter.getGraphGenerator().removeNode(nodeId);
        final ConsensusSnapshot snapshot = Objects.requireNonNull(
                        getOutput().getConsensusRounds().peekLast())
                .getSnapshot();
        intake.loadSnapshot(snapshot);
        // the above will clear all events from the linker and consensus, so we need to add all non-ancient events
        // adding events will also add the events to the output, so we make a copy of the list and add them back
        final LinkedList<PlatformEvent> added = new LinkedList<>(getOutput().getAddedEvents());
        getOutput().getAddedEvents().clear();
        for (final PlatformEvent e : added) {
            intake.addEvent(e.copyGossipedData());
        }
    }

    /**
     * Create a new {@link ConsensusTestNode} that will be created by simulating a reconnect with this context
     *
     * @param configuration the configuration to use for the new node
     * @param metrics the metrics to use for the new node
     * @param time the time to use for the new node
     * @return a new {@link ConsensusTestNode}
     */
    public @NonNull ConsensusTestNode reconnect(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        // create a new context
        final EventEmitter newEmitter = eventEmitter.cleanCopy(random.nextLong());
        newEmitter.reset();

        final ConsensusTestNode consensusTestNode = new ConsensusTestNode(
                newEmitter,
                new TestIntake(
                        configuration,
                        metrics,
                        time,
                        newEmitter.getGraphGenerator().getRoster()));
        consensusTestNode.intake.loadSnapshot(
                Objects.requireNonNull(getOutput().getConsensusRounds().peekLast())
                        .getSnapshot());

        assertThat(consensusTestNode.intake.getConsensusRounds())
                .withFailMessage("we should not have reached consensus yet")
                .isEmpty();

        return consensusTestNode;
    }

    /**
     * Adds a number of events from the emitter to the node
     *
     * @param numberOfEvents the number of events to add
     */
    public void addEvents(final long numberOfEvents) {
        for (int i = 0; i < numberOfEvents; i++) {
            intake.addEvent(eventEmitter.emitEvent());
        }
    }

    /**
     * @return the event emitter that produces events
     */
    public @NonNull EventEmitter getEventEmitter() {
        return eventEmitter;
    }

    /**
     * Get the latest round that has reached consensus.
     */
    public long getLatestRound() {
        return intake.getOutput().getLatestRound();
    }

    /**
     * @return the output of the consensus instance
     */
    public @NonNull ConsensusOutput getOutput() {
        return intake.getOutput();
    }

    public @NonNull TestIntake getIntake() {
        return intake;
    }

    public @NonNull Random getRandom() {
        return random;
    }
}
