// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Random;
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
     * @param platformContext the platform context
     * @param eventEmitter    the emitter of events
     */
    public static @NonNull ConsensusTestNode genesisContext(
            @NonNull final PlatformContext platformContext, @NonNull final EventEmitter eventEmitter) {
        return new ConsensusTestNode(
                eventEmitter,
                new TestIntake(
                        Objects.requireNonNull(platformContext),
                        eventEmitter.getGraphGenerator().getAddressBook()));
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
     * @param platformContext the platform context
     * @return a new {@link ConsensusTestNode}
     */
    public @NonNull ConsensusTestNode reconnect(@NonNull final PlatformContext platformContext) {
        // create a new context
        final EventEmitter newEmitter = eventEmitter.cleanCopy(random.nextLong());
        newEmitter.reset();

        final ConsensusTestNode consensusTestNode = new ConsensusTestNode(
                newEmitter,
                new TestIntake(platformContext, newEmitter.getGraphGenerator().getAddressBook()));
        consensusTestNode.intake.loadSnapshot(
                Objects.requireNonNull(getOutput().getConsensusRounds().peekLast())
                        .getSnapshot());

        assertTrue(consensusTestNode.intake.getConsensusRounds().isEmpty(), "we should not have reached consensus yet");

        return consensusTestNode;
    }

    /**
     * Adds a number of events from the emitter to the node
     *
     * @param numberOfEvents the number of events to add
     */
    public void addEvents(final long numberOfEvents) {
        for (int i = 0; i < numberOfEvents; i++) {
            intake.addEvent(eventEmitter.emitEvent().getBaseEvent());
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
