// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Computes and tracks tipsets for non-ancient events.
 *
 * <p>For each creator, a tipset holds the ordering key of its tip, the latest event of that creator in the ancestry.
 * Each tracker chooses its ordering keys. They can only be compared between events of the same creator, where a later
 * event has a higher key. Advancing this node's own tip never counts toward the advancement weight, so the ordering
 * keys of this node's tips are unspecified.
 *
 * <p>Events are added after their non-ancient parents. The tipsets a tracker returns must not be modified.
 */
public interface TipsetTracker {

    /**
     * Set the event window.
     *
     * @param eventWindow the current event window
     */
    void setEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Get the current event window (from this tracker's perspective).
     *
     * @return the event window
     */
    @NonNull
    EventWindow getEventWindow();

    /**
     * Add a new self event to the tracker.
     *
     * @param selfEventDesc the descriptor of the self event being added
     * @param parents       the parent descriptors of the self event being added
     * @return the tipset for the new self event
     */
    @NonNull
    Tipset addSelfEvent(@NonNull EventDescriptorWrapper selfEventDesc, @NonNull List<EventDescriptorWrapper> parents);

    /**
     * Add a new event, not created by this node, to the tracker.
     *
     * @param event the peer event to add
     * @return the tipset for the event that was added
     */
    @NonNull
    Tipset addPeerEvent(@NonNull PlatformEvent event);

    /**
     * Get the tipset of an event, or null if the event is not being tracked.
     *
     * @param eventDescriptorWrapper the fingerprint of the event
     * @return the tipset of the event, or null if the event is not being tracked
     */
    @Nullable
    Tipset getTipset(@NonNull EventDescriptorWrapper eventDescriptorWrapper);

    /**
     * Get the ordering key a tipset has for a particular node once it includes all events this tracker has added from
     * that node. A tipset with this key for the node leaves no event of the node to use as an other parent.
     *
     * @param nodeId the node in question
     * @return the ordering key of the latest event added from the node, or
     * {@link Tipset#ORDERING_KEY_UNDEFINED} if there is none
     */
    long getLatestTipOrderingKeyForNode(@NonNull NodeId nodeId);

    /**
     * Reset the tipset tracker to its initial state.
     */
    void clear();
}
