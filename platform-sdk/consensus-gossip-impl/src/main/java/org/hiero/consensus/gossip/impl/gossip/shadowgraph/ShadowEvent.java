// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.LinkedEvent;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A shadow event wraps a hashgraph event, and provides parent pointers to shadow events.
 * <p>
 * The shadow event type is the vertex type of the shadow graph. This is the elemental type of {@link Shadowgraph}.
 * It provides a reference to a hashgraph event instance and the following operations:
 *
 * <ul>
 * <li>linking of a parent shadow event</li>
 * <li>unlinking of a parent shadow event</li>
 * <li>querying for parent events</li>
 * </ul>
 *
 * All linking and unlinking of a shadow event is implemented by this type.
 * <p>
 * A shadow event never modifies the fields in a hashgraph event.
 */
public class ShadowEvent extends LinkedEvent<ShadowEvent> {

    /**
     * Construct a shadow event from an event and the shadow events of its parents
     *
     * @param platformEvent
     * 		the event
     * @param allParents
     * 		the parent event's shadows
     */
    public ShadowEvent(@NonNull final PlatformEvent platformEvent, @NonNull final List<ShadowEvent> allParents) {
        super(platformEvent, allParents);
    }

    /**
     * Construct a shadow event from an event
     *
     * @param event
     * 		the event
     */
    public ShadowEvent(final PlatformEvent event) {
        super(event, List.of());
    }
}
