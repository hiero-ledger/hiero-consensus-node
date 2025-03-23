// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.source;

import static com.swirlds.platform.test.fixtures.event.EventUtils.staticDynamicValue;

import com.swirlds.common.test.fixtures.TransactionGenerator;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.DynamicValue;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;

public interface EventSource {

    /**
     * Get an exact copy of this event source as it was in its original state. Child classes should invoke
     * the copy constructor {@link AbstractEventSource#AbstractEventSource(AbstractEventSource)}
     * when creating the copy.
     */
    EventSource copy();

    /**
     * Reset this event source to its original state. Does not undo manual settings changes.
     *
     * Child classes that override this method MUST call super.reset().
     */
    void reset();

    /**
     * Get the node ID of this event source.
     */
    @NonNull
    NodeId getNodeId();

    /**
     * Set the node ID of this source. MUST be called before any events are generated.
     * Automatically called by the StandardEventGenerator.
     *
     * @param nodeId
     * 		A unique integer that represents the ID of this node.
     * @return this
     */
    EventSource setNodeId(@NonNull NodeId nodeId);

    /**
     * Get the weight held by this event source.
     */
    long getWeight();

    /**
     * Get the probabilistic weight that this node will create the next new event. A node with a weight of 1 will
     * create new events half as often as a node with a weight of 2.0. A node with a weight of 0 will
     * never create new events.
     */
    double getNewEventWeight(Random random, long eventIndex);

    /**
     * Set the probabilistic weight that this node will create the next new event. A node with a weight of 1.0 will
     * create new events half as often as a node with a weight of 2.0. A node with a weight of 0.0 will
     * never create new events.
     *
     * @param newEventWeight
     * 		a constant weight
     * @return this
     */
    @SuppressWarnings("unchecked")
    default EventSource setNewEventWeight(final double newEventWeight) {
        setNewEventWeight(staticDynamicValue(newEventWeight));
        return this;
    }

    /**
     * Set the probabilistic weight that this node will create the next new event. A node with a weight of 1.0 will
     * create new events half as often as a node with a weight of 2.0. A node with a weight of 0.0 will never create new
     * events.
     *
     * @param dynamicWeight a weight that may change over time
     */
    void setNewEventWeight(DynamicValue<Double> dynamicWeight);

    /**
     * Set the transaction generator function used to generate transactions for events created by this source.
     *
     * @return this
     */
    EventSource setTransactionGenerator(final TransactionGenerator transactionGenerator);

    /**
     * Generates a new event. Is responsible for populating IndexedEvent metadata fields.
     *
     * @param random      a source of randomness
     * @param eventIndex  the unique index of the event that will be generated
     * @param otherParent the node that is contributing the "other parent" event
     * @param timestamp   the creation timesetamp that the event should have
     * @param birthRound  the pending consensus round when the event was created
     * @return The random event that was generated.
     */
    EventImpl generateEvent(
            @NonNull final Random random,
            final long eventIndex,
            @Nullable final EventSource otherParent,
            @NonNull final Instant timestamp,
            final long birthRound);

    /**
     * Get an event recently created by this node.
     *
     * @param index
     * 		0 refers to the latest event, 1 to the second latest, and so on
     * @return the event, if it exists. Null if there are no events, and the oldest possible if the event at the
     * 		requested index is no longer stored.
     */
    EventImpl getRecentEvent(Random random, int index);

    /**
     * Return the latest event that was generated.
     * Dishonest nodes may exhibit arbitrary behavior.
     *
     * @param random
     * 		a source of randomness
     */
    default EventImpl getLatestEvent(final Random random) {
        return getRecentEvent(random, 0);
    }

    /**
     * Sets the latest event.
     *
     * @param random
     * 		a source of randomness
     * @param event
     * 		an event that was just created by this source
     */
    void setLatestEvent(Random random, EventImpl event);

    /**
     * Get the event index (i.e. the age of the event) that this node would like to use for its other parent.
     */
    int getRequestedOtherParentAge(Random random, long eventIndex);

    /**
     * Used when this node is creating an event and wants to decide the age of the other parent, i.e.
     * when this node is requesting an other parent from another node.
     *
     * Dynamic value returns an index.
     *
     * An index of 0 means to take the most recent event from this node, an index of 1 means to take the second most
     * recent event, and so on. If the index requests an event that is no longer in memory or that does not exist,
     * the oldest event from the node is instead returned.
     *
     * By default, event source uses a random integer from a power law distribution that has a 95% chance of
     * requesting the most recent event.
     *
     * Every time an other parent is requested, the requesting node and the providing node each propose an index
     * (using the values from setOtherParentRequestIndex and setOtherParentProviderIndex). The largest
     * (i.e. the oldest) index is used.
     *
     * @return this
     */
    EventSource setRequestedOtherParentAgeDistribution(DynamicValue<Integer> otherParentIndex);

    /**
     * Get the event index (i.e. the age of the event) that this node would like to use for when it provides other
     * parents.
     */
    int getProvidedOtherParentAge(Random random, long eventIndex);

    /**
     * Used when this node is asked to provide an event (to be an other parent) to determine the age of the
     * provided event.
     *
     * Returns an index.
     *
     * An index of 0 means to take the most recent event from this node, an index of 1 means to take the second most
     * recent event, and so on. If the index requests an event that is no longer in memory or that does not exist,
     * the oldest event from the node is instead returned.
     *
     * By default, event source uses 0.
     *
     * Every time an other parent is requested, the requesting node and the providing node each propose an index
     * (using the values from setOtherParentRequestIndex and setOtherParentProviderIndex). The largest
     * (i.e. the oldest) index is used.
     *
     * @return this
     */
    EventSource setProvidedOtherParentAgeDistribution(DynamicValue<Integer> otherParentIndex);

    /**
     * Returns the number of recent events that are saved by this node. These recent events are used as the
     * other parents of new events.
     */
    int getRecentEventRetentionSize();

    /**
     * Configure the number of recent events that are saved by this node. These recent events are used as the
     * other parents of new events. By default 100 events are retained.
     *
     * @return this
     */
    EventSource setRecentEventRetentionSize(int recentEventRetentionSize);
}
