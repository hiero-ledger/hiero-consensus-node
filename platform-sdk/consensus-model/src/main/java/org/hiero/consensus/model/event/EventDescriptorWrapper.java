// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import com.hedera.hapi.platform.event.EventDescriptor;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

/**
 * A wrapper class for {@link EventDescriptor} that includes the hash of the event descriptor.
 */
public class EventDescriptorWrapper {

    private final EventDescriptor eventDescriptor;
    private final Hash hash;
    private final NodeId creator;

    private EventDescriptorWrapper(@NonNull final EventDescriptor eventDescriptor) {
        this.eventDescriptor = eventDescriptor;
        this.hash = new Hash(eventDescriptor.hash());
        this.creator = NodeId.of(eventDescriptor.creatorNodeId());
    }

    /**
     * Constructs a new {@link EventDescriptorWrapper}.
     *
     * @param hash the hash of the event descriptor
     * @param creator the creator of the event descriptor
     * @param birthRound the birth round of the event descriptor
     */
    public EventDescriptorWrapper(@NonNull final Hash hash, @NonNull final NodeId creator, final long birthRound) {
        this.eventDescriptor = EventDescriptor.newBuilder()
                .hash(hash.getBytes())
                .creatorNodeId(creator.id())
                .birthRound(birthRound)
                .build();
        this.hash = hash;
        this.creator = creator;
    }

    /**
     * Creates a new {@link EventDescriptorWrapper} from the given {@link EventDescriptor}.
     *
     * @param eventDescriptor the event descriptor to wrap
     * @return a new {@link EventDescriptorWrapper} instance
     */
    public static EventDescriptorWrapper fromPbj(@NonNull final EventDescriptor eventDescriptor) {
        return new EventDescriptorWrapper(eventDescriptor);
    }

    /**
     * Returns the {@link Hash} of this event descriptor.
     *
     * @return the hash of this event descriptor
     */
    public Hash hash() {
        return hash;
    }

    /**
     * Returns the {@link NodeId} of the creator of this event descriptor.
     *
     * @return the creator of this event descriptor
     */
    public NodeId creator() {
        return creator;
    }

    /**
     * Get this event's birth round. This can be used to determine if this event is ancient or not.
     *
     * @return the event's birth round
     */
    public long birthRound() {
        return eventDescriptor.birthRound();
    }

    /**
     * Returns the PBJ-representation of this event descriptor.
     *
     * @return the PBJ representation of this event descriptor
     */
    public EventDescriptor toPbj() {
        return eventDescriptor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String toString() {
        return String.format("(CR:%d H:%s BR:%d)", creator().id(), hash().toHex(6), birthRound());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final EventDescriptorWrapper that = (EventDescriptorWrapper) o;
        return eventDescriptor.equals(that.eventDescriptor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return eventDescriptor.hashCode();
    }
}
