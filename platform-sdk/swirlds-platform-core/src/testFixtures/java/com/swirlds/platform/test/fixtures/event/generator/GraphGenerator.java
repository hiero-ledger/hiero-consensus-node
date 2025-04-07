// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.generator;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.event.DynamicValue;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

/**
 * Generates a hashgraph of events.
 *
 */
public interface GraphGenerator {

    /**
     * Get the next event.
     */
    EventImpl generateEvent();

    /**
     * Get the number of sources (i.e. nodes) contained by this generator.
     */
    int getNumberOfSources();

    /**
     * Get the event source for a particular node ID.
     */
    EventSource getSource(@NonNull final NodeId nodeID);

    /**
     * Get the event source for a particular node index.
     */
    @NonNull
    EventSource getSourceByIndex(final int nodeIndex);

    /**
     * Get an exact copy of this event generator in its current state. The events returned by this
     * new generator will be equivalent to the events returned by this generator, but these events
     * will be distinct objects that are not inter-connected in any way.
     *
     * Note: if this generator has emitted a large number of events, this method may be expensive. The copied
     * generator needs to skip all events already emitted.
     */
    default GraphGenerator copy() {
        final GraphGenerator generator = cleanCopy();
        generator.skip(getNumEventsGenerated());
        return generator;
    }

    /**
     * Get an exact copy of this event generator as it was when it was first created.
     */
    GraphGenerator cleanCopy();

    /**
     * Reset this generator to its original state. Does not undo settings changes, just the events that have been
     * emitted.
     */
    void reset();

    /**
     * Get the total number of events that have been created by this generator.
     */
    long getNumEventsGenerated();

    /**
     * Skip a number of events. These events will not be returned by any methods.
     *
     * @param numberToSkip
     * 		The number of events to skip.
     */
    default void skip(final long numberToSkip) {
        for (long i = 0; i < numberToSkip; i++) {
            generateEvent();
        }
    }

    /**
     * Get the next sequence of events.
     *
     * @param numberOfEvents
     * 		The number of events to get.
     */
    default List<EventImpl> generateEvents(final int numberOfEvents) {
        final List<EventImpl> events = new ArrayList<>(numberOfEvents);
        for (int i = 0; i < numberOfEvents; i++) {
            events.add(generateEvent());
        }
        return events;
    }

    /**
     * Get an address book that represents the collection of nodes that are generating the events.
     */
    @NonNull
    @Deprecated(forRemoval = true)
    AddressBook getAddressBook();

    /**
     * Get the roster that represents the collection of nodes that are generating the events.
     */
    @NonNull
    Roster getRoster();

    /**
     * Returns the maximum generation of this event generator.
     *
     * @param creatorId
     * 		the event creator
     * @return the maximum event generation for the supplied creator
     */
    long getMaxGeneration(@Nullable final NodeId creatorId);

    /**
     * Returns the maximum birth round of this event generator.
     *
     * @param creatorId
     * 		the event creator
     * @return the maximum event birth round for the supplied creator
     */
    long getMaxBirthRound(@Nullable final NodeId creatorId);

    /**
     * Returns the maximum generation of all events created by this generator
     */
    long getMaxGeneration();

    /**
     * Set the affinity of each node for choosing the parents of its events.
     *
     * @param affinityMatrix
     * 		An n by n matrix where n is the number of event sources. Each row defines the preference of a particular
     * 		node when choosing other parents. The node at index 0 in the address book is described by the first row,
     * 		the node at index 1 in the address book by the next row, etc. Each entry should be a weight. Weights of
     * 		self (i.e. the weights on the diagonal) should be 0.
     */
    void setOtherParentAffinity(final List<List<Double>> affinityMatrix);

    /**
     * Set the affinity of each node for choosing the parents of its events.
     *
     * @param affinityMatrix
     * 		A dynamic n by n matrix where n is the number of event sources. Each row defines the preference of a
     * 		particular node when choosing other parents. The node at index 0 in the address book is described by
     * 		the first row, the node at index 1 in the address book by the next row, etc. Each entry should be a weight.
     * 		Weights of self (i.e. the weights on the diagonal) should be 0.
     */
    void setOtherParentAffinity(final DynamicValue<List<List<Double>>> affinityMatrix);

    /**
     * Remove a node from the generator. This will remove it from the address book and it will remove its event source,
     * so that this node will not generate any more events.
     * NOTE: This method is created specifically for a single node removal. For more complex address book changes this
     * functionality should be expanded.
     * @param nodeId the node to remove
     */
    void removeNode(@NonNull final NodeId nodeId);
}
