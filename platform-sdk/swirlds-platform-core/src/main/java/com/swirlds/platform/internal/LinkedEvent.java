package com.swirlds.platform.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;

public class LinkedEvent<T extends LinkedEvent<T>> {
    private final PlatformEvent platformEvent;
    /** the self parent of this */
    private T selfParent;
    /** the other-parents of this */
    private List<T> otherParents;
    /** the parents of this */
    private List<T> allParents;

    public LinkedEvent(@NonNull final PlatformEvent platformEvent, @NonNull final List<T> allParents) {
        this.platformEvent = Objects.requireNonNull(platformEvent);
        this.allParents = Objects.requireNonNull(allParents, "allParents");
        if (!allParents.isEmpty() && allParents.getFirst().getPlatformEvent().getCreatorId().equals(platformEvent.getCreatorId())) {
            // this event DOES have a self parent that is linked
            this.selfParent = allParents.getFirst();
            this.otherParents = allParents.subList(1, allParents.size());
        } else {
            // this event DOESN'T have a self parent that is linked
            this.selfParent = null;
            this.otherParents = allParents;
        }
    }

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
     * Erase all references to other events within this event. This can be used so other events can
     * be garbage collected, even if this one still has things pointing to it.
     */
    public void clear() {
        selfParent = null;
        otherParents = List.of();
        allParents = List.of();
    }
}
