// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Links events to their parents. Expects events to be provided in topological order.
 * <p>
 * Will not link events to parents in the following cases:
 * <ul>
 *     <li>The parent is ancient</li>
 *     <li>The parent's generation does not match the generation claimed by the child event</li>
 *     <li>The parent's time created is greater than or equal to the child's time created</li>
 * </ul>
 * Note: This class doesn't have a direct dependency on the {@link Shadowgraph ShadowGraph},
 * but it is dependent in the sense that the Shadowgraph is currently responsible for eventually unlinking events.
 */
public interface InOrderLinker {

    /**
     * Find and link the parents of the given event.
     *
     * @param event the event to link
     * @return the linked event, or null if the event is ancient
     */
    @Nullable
    @InputWireLabel("events to gossip") // Note: this interface is only used as a fully fledged component by gossip
    EventImpl linkEvent(@NonNull PlatformEvent event);

    /**
     * Set the event window, defining the minimum non-ancient threshold.
     *
     * @param eventWindow the event window
     */
    @InputWireLabel("event window")
    void setEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Clear the internal state of this linker.
     */
    void clear();
}
