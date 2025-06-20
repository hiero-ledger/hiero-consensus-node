// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.source;

import static com.swirlds.platform.test.fixtures.event.EventUtils.integerPowerDistribution;
import static com.swirlds.platform.test.fixtures.event.EventUtils.staticDynamicValue;

import com.swirlds.common.test.fixtures.TransactionGenerator;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.DynamicValue;
import com.swirlds.platform.test.fixtures.event.DynamicValueGenerator;
import com.swirlds.platform.test.fixtures.event.RandomEventUtils;
import com.swirlds.platform.test.fixtures.event.TransactionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;

/**
 * A source of events.
 */
public abstract class AbstractEventSource implements EventSource {

    /**
     * The unique ID of this source/node. Is set by the StandardEventGenerator.
     */
    private NodeId nodeId;

    /**
     * Influences the probability that this node create a new event.
     */
    private DynamicValueGenerator<Double> newEventWeight;

    /**
     * The average size of a transaction, in bytes.
     */
    private static final double DEFAULT_AVG_TX_SIZE = 3;

    /**
     * The standard deviation of the size of a transaction, in bytes.
     */
    private static final double DEFAULT_TX_SIZE_STD_DEV = 1;

    /**
     * The average number of transactions.
     */
    private static final double DEFAULT_TX_COUNT_AVG = 3;

    /**
     * The standard deviation of the number of transactions.
     */
    private static final double DEFAULT_TX_COUNT_STD_DEV = 3;

    /** The default transaction generator used to create transaction for generated events. */
    protected static final TransactionGenerator DEFAULT_TRANSACTION_GENERATOR =
            r -> TransactionUtils.randomApplicationTransactions(
                    r, DEFAULT_AVG_TX_SIZE, DEFAULT_TX_SIZE_STD_DEV, DEFAULT_TX_COUNT_AVG, DEFAULT_TX_COUNT_STD_DEV);

    /**
     * The number of events that have been emitted by this event source.
     */
    private long eventCount;

    /**
     * A dynamic value generator that is used to determine the age of the other parent (for when we ask another node for
     * the other parent)
     */
    private DynamicValueGenerator<Integer> otherParentRequestIndex;

    /**
     * A dynamic value generator that is used to determine the age of the other parent (for when another node is
     * requesting the other parent from us)
     */
    private DynamicValueGenerator<Integer> otherParentProviderIndex;

    /**
     * The number of recent events that this node is expected to store.
     */
    private int recentEventRetentionSize;

    /** Supplier of transactions for emitted events **/
    private TransactionGenerator transactionGenerator;

    private final boolean useFakeHashes;

    /**
     * Creates a new instance with the supplied transaction generator and weight.
     *
     * @param useFakeHashes        indicates if fake hashes should be used instead of real ones
     * @param transactionGenerator a transaction generator to use when creating events
     */
    protected AbstractEventSource(final boolean useFakeHashes, final TransactionGenerator transactionGenerator) {
        this.useFakeHashes = useFakeHashes;
        this.transactionGenerator = transactionGenerator;
        nodeId = NodeId.UNDEFINED_NODE_ID;
        setNewEventWeight(1.0);

        eventCount = 0;

        // with high probability, request the most recent event as an other parent to this node's events
        otherParentRequestIndex = new DynamicValueGenerator<>(integerPowerDistribution(0.95));

        // initialize to always provide the most recent parent for nodes requesting an event as an other parent
        otherParentProviderIndex = new DynamicValueGenerator<>(staticDynamicValue(0));

        recentEventRetentionSize = 100;
    }

    protected AbstractEventSource(final AbstractEventSource that) {
        this.useFakeHashes = that.useFakeHashes;
        this.transactionGenerator = that.transactionGenerator;
        this.nodeId = that.nodeId;
        this.newEventWeight = that.newEventWeight.cleanCopy();

        this.eventCount = 0;
        this.otherParentRequestIndex = that.otherParentRequestIndex.cleanCopy();
        this.otherParentProviderIndex = that.otherParentProviderIndex.cleanCopy();
        this.recentEventRetentionSize = that.recentEventRetentionSize;
    }

    /**
     * Maintain the maximum size of a list of events by removing the last element (if needed). Utility method that is
     * useful for child classes.
     */
    protected void pruneEventList(final LinkedList<EventImpl> events) {
        if (events.size() > getRecentEventRetentionSize()) {
            events.removeLast();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        eventCount = 0;
        otherParentRequestIndex.reset();
        otherParentProviderIndex.reset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeId getNodeId() {
        return nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource setNodeId(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.nodeId = nodeId;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getNewEventWeight(final Random random, final long eventIndex) {
        return newEventWeight.get(random, eventIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setNewEventWeight(final DynamicValue<Double> dynamicWeight) {
        this.newEventWeight = new DynamicValueGenerator<>(dynamicWeight);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventImpl generateEvent(
            @NonNull final Random random,
            final long eventIndex,
            @Nullable final EventSource otherParent,
            @NonNull final Instant timestamp,
            final long birthRound) {
        Objects.requireNonNull(random);
        Objects.requireNonNull(timestamp);
        final EventImpl event;

        // The higher the index, the older the event. Use the oldest parent between the provided and requested value.
        final int otherParentIndex = Math.max(
                // event index (event age) that this node wants to use as it's other parent
                getRequestedOtherParentAge(random, eventIndex),
                // event index (event age) that the other node wants to provide as an other parent to this node
                otherParent.getProvidedOtherParentAge(random, eventIndex));

        final EventImpl otherParentEvent =
                otherParent == null ? null : otherParent.getRecentEvent(random, otherParentIndex);
        final EventImpl latestSelfEvent = getLatestEvent(random);

        event = RandomEventUtils.randomEventWithTimestamp(
                random,
                nodeId,
                timestamp,
                birthRound,
                transactionGenerator.generate(random),
                latestSelfEvent,
                otherParentEvent,
                useFakeHashes);

        eventCount++;

        // Although we could just set latestEvent directly, calling this method allows for dishonest implementations
        // to override standard behavior.
        setLatestEvent(random, event);

        return event;
    }

    /**
     * {@inheritDoc}
     *
     * @param transactionGenerator
     */
    public EventSource setTransactionGenerator(final TransactionGenerator transactionGenerator) {
        this.transactionGenerator = transactionGenerator;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRequestedOtherParentAge(final Random random, final long eventIndex) {
        return otherParentRequestIndex.get(random, eventIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource setRequestedOtherParentAgeDistribution(final DynamicValue<Integer> otherParentIndex) {
        otherParentRequestIndex = new DynamicValueGenerator<>(otherParentIndex);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getProvidedOtherParentAge(final Random random, final long eventIndex) {
        return otherParentProviderIndex.get(random, eventIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource setProvidedOtherParentAgeDistribution(final DynamicValue<Integer> otherParentIndex) {
        this.otherParentProviderIndex = new DynamicValueGenerator<>(otherParentIndex);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRecentEventRetentionSize() {
        return recentEventRetentionSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource setRecentEventRetentionSize(final int recentEventRetentionSize) {
        this.recentEventRetentionSize = recentEventRetentionSize;
        return this;
    }
}
