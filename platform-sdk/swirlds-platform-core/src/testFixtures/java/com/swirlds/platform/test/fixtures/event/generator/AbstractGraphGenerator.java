// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.generator;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.node.NodeId;

/**
 * A base graph generator class that provides most functionality of a graph generator except for determining how to
 * generate the next event.
 */
public abstract class AbstractGraphGenerator implements GraphGenerator {

    /**
     * The total number of events that have been emitted by this generator.
     */
    private long numEventsGenerated;

    /**
     * The initial seed of this generator.
     */
    private final long initialSeed;

    /**
     * The source of all randomness for this class.
     */
    private Random random;

    /** The highest birth round of created events for each creator */
    private final Map<NodeId, Long> maxBirthRoundPerCreator;

    protected AbstractGraphGenerator(final long initialSeed) {
        this.initialSeed = initialSeed;
        random = new Random(initialSeed);
        maxBirthRoundPerCreator = new HashMap<>();
    }

    /**
     * Child classes should reset internal metadata in this method.
     */
    protected abstract void resetInternalData();

    /**
     * {@inheritDoc}
     * <p>
     * Child classes must call super.reset() if they override this method.
     */
    @Override
    public final void reset() {
        numEventsGenerated = 0;
        random = new Random(initialSeed);
        maxBirthRoundPerCreator.clear();
        resetInternalData();
    }

    /**
     * Build the event that will be returned by getNextEvent.
     *
     * @param eventIndex the index of the event to build
     */
    protected abstract EventImpl buildNextEvent(long eventIndex);

    /**
     * {@inheritDoc}
     */
    public final EventImpl generateEvent() {
        final EventImpl next = generateEventWithoutIndex();

        return next;
    }

    /**
     * The same as {@link #generateEvent()}, but does not set the stream sequence number.
     */
    public final EventImpl generateEventWithoutIndex() {
        final EventImpl next = buildNextEvent(numEventsGenerated);
        next.getBaseEvent().signalPrehandleCompletion();
        numEventsGenerated++;
        updateMaxBirthRound(next);
        return next;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final long getNumEventsGenerated() {
        return numEventsGenerated;
    }

    /**
     * Get the Random object to be used by this class.
     */
    protected final Random getRandom() {
        return random;
    }

    /**
     * The seed used at the start of this generator.
     */
    public final long getInitialSeed() {
        return initialSeed;
    }

    private void updateMaxBirthRound(@NonNull final EventImpl event) {
        maxBirthRoundPerCreator.merge(event.getCreatorId(), event.getBirthRound(), Math::max);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getMaxBirthRound(@Nullable final NodeId creatorId) {
        return maxBirthRoundPerCreator.getOrDefault(creatorId, ConsensusConstants.ROUND_NEGATIVE_INFINITY);
    }
}
