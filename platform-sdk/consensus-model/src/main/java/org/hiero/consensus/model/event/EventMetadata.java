// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.hiero.base.crypto.AbstractHashable;
import org.hiero.base.utility.ListUtils;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.TransactionWrapper;

/**
 * Metadata for an event that can be derived from the event itself
 */
public class EventMetadata extends AbstractHashable {
    /**
     * ID of this event's creator (translate before sending)
     */
    private final NodeId creatorId;

    /**
     * the self parent event descriptor
     */
    private final EventDescriptorWrapper selfParent;

    /**
     * the other parents' event descriptors
     */
    private final List<EventDescriptorWrapper> otherParents;

    /** a combined immutable list of all parents, selfParent + otherParents */
    private final List<EventDescriptorWrapper> allParents;

    /**
     * creation time, as claimed by its creator
     */
    private final Instant timeCreated;

    /**
     * an immutable list of transactions
     */
    private final List<TransactionWrapper> transactions;
    /**
     * The event descriptor for this event. Is not itself hashed.
     */
    private EventDescriptorWrapper descriptor;

    /**
     * The birth round that was initialized to the event.
     */
    private final long birthRound;

    /**
     * Create a EventMetadata object
     *
     * @param creatorId    ID of this event's creator
     * @param allParents   all parent event descriptors
     * @param timeCreated  creation time, as claimed by its creator
     * @param transactions list of transactions included in this event instance
     * @param birthRound   birth round associated with event
     */
    public EventMetadata(
            @NonNull final NodeId creatorId,
            @NonNull final List<EventDescriptorWrapper> allParents,
            @NonNull final Instant timeCreated,
            @NonNull final List<Bytes> transactions,
            final long birthRound) {

        requireNonNull(transactions, "The transactions must not be null");
        this.creatorId = requireNonNull(creatorId, "The creatorId must not be null");
        this.allParents = List.copyOf(requireNonNull(allParents, "The allParents must not be null"));
        if (!this.allParents.isEmpty() && this.allParents.getFirst().creator().equals(creatorId)) {
            // this event has a self parent
            this.selfParent = this.allParents.getFirst();
            this.otherParents = this.allParents.subList(1, this.allParents.size());
        } else {
            // this event does not have a self parent
            this.selfParent = null;
            this.otherParents = this.allParents;
        }
        this.timeCreated = requireNonNull(timeCreated, "The timeCreated must not be null");
        // mapToCopy returns a new list that nothing else references, so a read-only view is enough
        this.transactions = Collections.unmodifiableList(ListUtils.mapToCopy(transactions, TransactionWrapper::new));
        this.birthRound = birthRound;
    }

    /**
     * Create a EventMetadata object
     *
     * @param gossipEvent the gossip event to extract metadata from
     */
    public EventMetadata(@NonNull final GossipEvent gossipEvent) {
        this(
                NodeId.of(requireNonNull(gossipEvent.eventCore(), "The eventCore must not be null")
                        .creatorNodeId()),
                ListUtils.mapToCopy(gossipEvent.parents(), EventDescriptorWrapper::fromPbj),
                HapiUtils.asInstant(
                        requireNonNull(gossipEvent.eventCore().timeCreated(), "The timeCreated must not be null")),
                gossipEvent.transactions(),
                gossipEvent.eventCore().birthRound());
    }

    /**
     * The birth round of the event
     *
     * @return the birth round for this event
     */
    public long getBirthRound() {
        return birthRound;
    }

    /**
     * The ID of the node that created this event.
     *
     * @return the ID of the node that created this event
     */
    @NonNull
    public NodeId getCreatorId() {
        return creatorId;
    }

    /**
     * Get the event descriptor for the self parent.
     *
     * @return the event descriptor for the self parent
     */
    @Nullable
    public EventDescriptorWrapper getSelfParent() {
        return selfParent;
    }

    /**
     * Get the event descriptors for the other parents.
     *
     * @return the event descriptors for the other parents
     */
    @NonNull
    public List<EventDescriptorWrapper> getOtherParents() {
        return otherParents;
    }

    /**
     * Check if the event has other parents.
     *
     * @return true if the event has other parents
     */
    public boolean hasOtherParents() {
        return otherParents != null && !otherParents.isEmpty();
    }

    /** @return a list of all parents, self parent (if any), + all other parents */
    @NonNull
    public List<EventDescriptorWrapper> getAllParents() {
        return allParents;
    }

    @NonNull
    public Instant getTimeCreated() {
        return timeCreated;
    }

    /**
     * @return list of transactions wrappers
     */
    @NonNull
    public List<TransactionWrapper> getTransactions() {
        return transactions;
    }

    /**
     * Get the event descriptor for this event, creating one if it hasn't yet been created. If called more than once
     * then return the same instance.
     *
     * @return an event descriptor for this event
     * @throws IllegalStateException if called prior to this event being hashed
     */
    @NonNull
    public EventDescriptorWrapper getDescriptor() {
        if (descriptor == null) {
            if (getHash() == null) {
                throw new IllegalStateException("The hash of the event must be set before creating the descriptor");
            }
            descriptor = new EventDescriptorWrapper(getHash(), creatorId, getBirthRound());
        }

        return descriptor;
    }
}
