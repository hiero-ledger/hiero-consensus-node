/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.system.events;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.AbstractHashable;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.system.transaction.TransactionWrapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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

    /** a combined list of all parents, selfParent + otherParents */
    private final List<EventDescriptorWrapper> allParents;

    private final long generation;

    /**
     * creation time, as claimed by its creator
     */
    private final Instant timeCreated;

    /**
     * list of transactions
     */
    private final List<TransactionWrapper> transactions;
    /**
     * The event descriptor for this event. Is not itself hashed.
     */
    private EventDescriptorWrapper descriptor;

    /**
     * Create a EventMetadata object
     *
     * @param creatorId       ID of this event's creator
     * @param selfParent      self parent event descriptor
     * @param otherParents    other parent event descriptors
     * @param timeCreated     creation time, as claimed by its creator
     * @param transactions    list of transactions included in this event instance
     */
    public EventMetadata(
            @NonNull final NodeId creatorId,
            @Nullable final EventDescriptorWrapper selfParent,
            @NonNull final List<EventDescriptorWrapper> otherParents,
            @NonNull final Instant timeCreated,
            @NonNull final List<Bytes> transactions) {

        Objects.requireNonNull(transactions, "The transactions must not be null");
        this.creatorId = Objects.requireNonNull(creatorId, "The creatorId must not be null");
        this.selfParent = selfParent;
        this.otherParents = List.copyOf(otherParents); // checks for null values and makes a copy
        this.allParents = selfParent == null
                ? this.otherParents
                : Stream.concat(Stream.of(selfParent), otherParents.stream()).toList();
        this.generation = calculateGeneration(allParents);
        this.timeCreated = Objects.requireNonNull(timeCreated, "The timeCreated must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null").stream()
                .map(TransactionWrapper::new)
                .toList();
    }

    /**
     * Create a EventMetadata object
     *
     * @param gossipEvent     the gossip event to extract metadata from
     */
    public EventMetadata(@NonNull final GossipEvent gossipEvent) {
        Objects.requireNonNull(gossipEvent.eventCore(), "The eventCore must not be null");
        this.creatorId = NodeId.of(gossipEvent.eventCore().creatorNodeId());
        this.allParents = gossipEvent.eventCore().parents().stream()
                .map(EventDescriptorWrapper::new)
                .toList();
        if (!allParents.isEmpty() && allParents.getFirst().creator().equals(creatorId)) {
            // this event has a self parent
            this.selfParent = allParents.getFirst();
            this.otherParents = allParents.subList(1, allParents.size());
        } else {
            // this event does not have a self parent
            this.selfParent = null;
            this.otherParents = allParents;
        }
        this.generation = calculateGeneration(allParents);
        this.timeCreated = HapiUtils.asInstant(
                Objects.requireNonNull(gossipEvent.eventCore().timeCreated(), "The timeCreated must not be null"));
        this.transactions =
                gossipEvent.transactions().stream().map(TransactionWrapper::new).toList();
    }

    private static long calculateGeneration(@NonNull final List<EventDescriptorWrapper> allParents) {
        return 1
                + Objects.requireNonNull(allParents).stream()
                        .mapToLong(d -> d.eventDescriptor().generation())
                        .max()
                        .orElse(EventConstants.GENERATION_UNDEFINED);
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
     * Get the hash of the self parent.
     *
     * @return the hash of the self parent
     */
    @Nullable
    public Hash getSelfParentHash() {
        if (selfParent == null) {
            return null;
        }
        return selfParent.hash();
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
    public boolean hasOtherParent() {
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

    public long getGeneration() {
        return generation;
    }

    /**
     * Get the event descriptor for this event, creating one if it hasn't yet been created. If called more than once
     * then return the same instance.
     *
     * @return an event descriptor for this event
     * @throws IllegalStateException if called prior to this event being hashed
     */
    @NonNull
    public EventDescriptorWrapper getDescriptor(final long birthRound) {
        if (descriptor == null) {
            if (getHash() == null) {
                throw new IllegalStateException("The hash of the event must be set before creating the descriptor");
            }

            descriptor = new EventDescriptorWrapper(
                    new EventDescriptor(getHash().getBytes(), creatorId.id(), birthRound, getGeneration()));
        }

        return descriptor;
    }
}
