// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Emits events in a random (but topologically correct) order.
 */
public class ShuffledEventEmitter extends BufferingEventEmitter {

    /** Source of randomness for selecting the next event to emit. */
    private Random random;

    private final long initialSeed;

    /**
     * Creates a new instance.
     *
     * @param seed
     * 		the seed to use for randomness
     * @param graphGenerator
     * 		creates the graph of events to be emitted
     */
    public ShuffledEventEmitter(final GraphGenerator graphGenerator, final long seed) {
        super(graphGenerator);
        this.initialSeed = seed;
        random = new Random(seed);
    }

    private ShuffledEventEmitter(final ShuffledEventEmitter that) {
        this(that.getGraphGenerator().cleanCopy(), that.initialSeed);
        this.setCheckpoint(that.getCheckpoint());
    }

    private ShuffledEventEmitter(final ShuffledEventEmitter that, final long seed) {
        this(that.getGraphGenerator().cleanCopy(), seed);
        this.setCheckpoint(that.getCheckpoint());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventImpl emitEvent() {
        // Randomly pick a creator node with even distribution. The logic determining if the event should be emitted
        // will match the creator weights of the graph generator unless one of those weights is zero.

        // Emit the next event from that node if possible, otherwise choose another random event.
        int attempts = 0;
        while (true) {
            final int nodeIndex = random.nextInt(getGraphGenerator().getNumberOfSources());
            final NodeId nodeID = RosterUtils.getNodeId(getGraphGenerator().getRoster(), nodeIndex);
            attemptToGenerateEventFromNode(nodeID);
            if (isReadyToEmitEvent(nodeID)) {
                eventEmittedFromBuffer();
                return events.get(nodeID).remove();
            }
            attempts++;
            if (attempts > 1000) {
                throw new RuntimeException("Cannot find event to emit");
            }
        }
    }

    /**
     * Returns a copy of this object as it was first created.
     */
    @Override
    public ShuffledEventEmitter cleanCopy() {
        return new ShuffledEventEmitter(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ShuffledEventEmitter cleanCopy(final long seed) {
        return new ShuffledEventEmitter(this, seed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        super.reset();
        random = new Random(initialSeed);
    }
}
