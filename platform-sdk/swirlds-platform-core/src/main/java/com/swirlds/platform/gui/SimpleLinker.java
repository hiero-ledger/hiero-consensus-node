// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui;

import com.swirlds.platform.event.EventCounter;
import com.swirlds.platform.event.linking.InOrderLinker;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.sequence.map.SequenceMap;
import com.swirlds.platform.sequence.map.StandardSequenceMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Similar to the {@link InOrderLinker} but simplified and streamlined. Also does unlinking and supports queries against
 * the non-ancient event set. Will soon be replaced by an instance of the ConsensusEngine once that class has been
 * disentangled from the shadowgraph.
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

    private final AncientMode ancientMode;
    private long nonAncientThreshold = 0;

    /**
     * Constructor
     *
     * @param ancientMode the ancient mode
     */
    public SimpleLinker(@NonNull final AncientMode ancientMode) {
        this.ancientMode = ancientMode;
        this.parentDescriptorMap = new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, ancientMode::selectIndicator);
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

        if (parentDescriptor.getAncientIndicator(AncientMode.GENERATION_THRESHOLD) < nonAncientThreshold) {
            // ancient parents don't need to be linked
            return null;
        }

        final EventImpl candidateParent = parentHashMap.get(parentDescriptor.hash());
        if (candidateParent == null) {
            return null;
        }

        if (candidateParent.getGeneration()
                != parentDescriptor.eventDescriptor().generation()) {
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
        if (ancientMode.selectIndicator(event) < nonAncientThreshold) {
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
     * Get all non-ancient events tracked by this linker sorted in topological order.
     *
     * @return all non-ancient events
     */
    @NonNull
    public List<EventImpl> getSortedNonAncientEvents() {
        return parentHashMap.values().stream()
                .sorted(Comparator.comparing(EventImpl::getGeneration))
                .toList();
    }

    /**
     * Clear the internal state of this linker.
     */
    public void clear() {
        parentDescriptorMap.clear();
        parentHashMap.clear();
    }
}
