// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import com.swirlds.platform.event.EventCounter;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * Links events to their parents. Expects events to be provided in topological order. Also does unlinking and supports
 * queries against the non-ancient event set.
 */
public class SimpleLinker {
    /**
     * The initial capacity of the {@link #parentDescriptorMap} and {@link #parentHashMap}
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * A sequence map from event descriptor to event.
     * <p>
     * The window of this map is shifted when the minimum non-ancient threshold is changed, so that only non-ancient
     * events are retained.
     */
    private final SequenceMap<EventDescriptorWrapper, EventImpl> parentDescriptorMap;

    /**
     * A map from event hash to event.
     * <p>
     * This map is needed in addition to the sequence map, since we need to be able to look up parent events based on
     * hash. Elements are removed from this map when the window of the sequence map is shifted.
     */
    private final Map<Hash, EventImpl> parentHashMap = new HashMap<>(INITIAL_CAPACITY);

    private long nonAncientThreshold = 0;

    /**
     * Constructor
     *
     */
    public SimpleLinker() {
        this.parentDescriptorMap =
                new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);
    }

    /**
     * Find the correct parent to link to a child. If a parent should not be linked, null is returned.
     * <p>
     * A parent should not be linked if any of the following are true:
     * <ul>
     *     <li>The parent is ancient</li>
     *     <li>The parent's generation does not match the generation claimed by the child event</li>
     *     <li>The parent's birthRound does not match the claimed birthRound by the child event</li>
     *     <li>The parent's time created is greater than or equal to the child's time created</li>
     * </ul>
     *
     * @param child            the child event
     * @param parentDescriptor the event descriptor for the claimed parent
     * @return the parent to link, or null if no parent should be linked
     */
    @Nullable
    private EventImpl getParentToLink(
            @NonNull final PlatformEvent child, @Nullable final EventDescriptorWrapper parentDescriptor) {

        if (parentDescriptor == null) {
            // There is no claimed parent for linking.
            return null;
        }

        if (parentDescriptor.eventDescriptor().birthRound() < nonAncientThreshold) {
            // ancient parents don't need to be linked
            return null;
        }

        final EventImpl candidateParent = parentHashMap.get(parentDescriptor.hash());
        if (candidateParent == null) {
            return null;
        }

        if (candidateParent.getBirthRound()
                != parentDescriptor.eventDescriptor().birthRound()) {
            return null;
        }

        final Instant parentTimeCreated = candidateParent.getBaseEvent().getTimeCreated();
        final Instant childTimeCreated = child.getTimeCreated();

        // only do this check for self parent, since the event creator doesn't consider other parent creation time
        // when deciding on the event creation time
        if (parentDescriptor.creator().equals(child.getDescriptor().creator())
                && parentTimeCreated.compareTo(childTimeCreated) >= 0) {
            return null;
        }

        return candidateParent;
    }

    /**
     * Find and link the parents of the given event.
     *
     * @param event the event to link
     * @return the linked event, or null if the event is ancient
     */
    @Nullable
    public EventImpl linkEvent(@NonNull final PlatformEvent event) {
        if (event.getBirthRound() < nonAncientThreshold) {
            // This event is ancient, so we don't need to link it.
            return null;
        }

        final EventImpl selfParent = getParentToLink(event, event.getSelfParent());

        // FUTURE WORK: Extend other parent linking to support multiple other parents.
        // Until then, take the first parent in the list.
        final List<EventDescriptorWrapper> otherParents = event.getOtherParents();
        final EventImpl otherParent = otherParents.isEmpty() ? null : getParentToLink(event, otherParents.get(0));

        final EventImpl linkedEvent = new EventImpl(event, selfParent, otherParent);
        EventCounter.incrementLinkedEventCount();

        final EventDescriptorWrapper eventDescriptorWrapper = event.getDescriptor();
        parentDescriptorMap.put(eventDescriptorWrapper, linkedEvent);
        parentHashMap.put(eventDescriptorWrapper.hash(), linkedEvent);

        return linkedEvent;
    }

    /**
     * Set the event window, defining the minimum non-ancient threshold.
     *
     * @param nonAncientThreshold the new non-ancient threshold
     */
    public void setNonAncientThreshold(final long nonAncientThreshold) {
        this.nonAncientThreshold = nonAncientThreshold;
        parentDescriptorMap.shiftWindow(nonAncientThreshold, (descriptor, event) -> {
            parentHashMap.remove(descriptor.hash());
            event.clear();
        });
    }

    /**
     * Get all non-ancient events tracked by this linker.
     *
     * @return all non-ancient events
     */
    @NonNull
    public List<EventImpl> getNonAncientEvents() {
        return parentHashMap.values().stream().toList();
    }

    /**
     * Clear the internal state of this linker.
     */
    public void clear() {
        parentDescriptorMap.clear();
        parentHashMap.clear();
    }
}
