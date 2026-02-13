// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import org.hiero.base.crypto.Hash;

public class LinkedEvent<T extends LinkedEvent<T>> {
    /** the event we are wrapping with links */
    private final PlatformEvent platformEvent;
    /** the self parent of this */
    private T selfParent;
    /** the other-parents of this */
    private List<T> otherParents;
    /** the parents of this */
    private List<T> allParents;

    /**
     * Create a new linked event
     *
     * @param platformEvent
     * 		the platform event to wrap
     * @param allParents
     * 		all parents of this event, including self parent if it exists
     */
    public LinkedEvent(@NonNull final PlatformEvent platformEvent, @NonNull final List<T> allParents) {
        this.platformEvent = Objects.requireNonNull(platformEvent);
        this.allParents = Objects.requireNonNull(allParents, "allParents");
        if (!allParents.isEmpty()
                && allParents.getFirst().getPlatformEvent().getCreatorId().equals(platformEvent.getCreatorId())) {
            // this event DOES have a self parent that is linked
            this.selfParent = allParents.getFirst();
            this.otherParents = allParents.subList(1, allParents.size());
        } else {
            // this event DOESN'T have a self parent that is linked
            this.selfParent = null;
            this.otherParents = allParents;
        }
    }

    /**
     * @return the platform event wrapped by this
     */
    public PlatformEvent getPlatformEvent() {
        return platformEvent;
    }

    /**
     * @return the self parent of this
     */
    public @Nullable T getSelfParent() {
        return selfParent;
    }

    /**
     * @return the other parents of this
     */
    public @NonNull List<T> getOtherParents() {
        return otherParents;
    }

    /**
     * @return all parents of this
     */
    public @NonNull List<T> getAllParents() {
        return allParents;
    }

    /**
     * @return returns {@link PlatformEvent#getHash()}}
     */
    public Hash getBaseHash() {
        return platformEvent.getHash();
    }

    /**
     * Erase all references to other events within this event. This can be used so other events can
     * be garbage collected, even if this one still has things pointing to it.
     */
    public void clear() {
        selfParent = null;
        otherParents = List.of();
        allParents = List.of();
    }

    /**
     * Two linked events are equal iff their reference platform event hashes are equal.
     *
     * @return true iff {@code this} and {@code o} reference platform event hashes that compare equal
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof final LinkedEvent<?> le)) {
            return false;
        }

        return getBaseHash().equals(le.getBaseHash());
    }

    /**
     * The hash code of a linked event is the hash of the platform event which this linked event references.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return getBaseHash().hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return platformEvent.toString();
    }
}
