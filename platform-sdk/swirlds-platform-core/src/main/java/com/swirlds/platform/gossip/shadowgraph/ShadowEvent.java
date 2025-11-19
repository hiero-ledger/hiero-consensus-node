// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import com.swirlds.platform.internal.LinkedEvent;
import java.util.Objects;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A shadow event wraps a hashgraph event, and provides parent pointers to shadow events.
 *
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
 *
 * A shadow event never modifies the fields in a hashgraph event.
 */
public class ShadowEvent extends LinkedEvent<ShadowEvent> {

    /**
     * Construct a shadow event from an event and the shadow events of its parents
     *
     * @param event
     * 		the event
     * @param selfParent
     * 		the self-parent event's shadow
     * @param otherParent
     * 		the other-parent event's shadow
     */
    public ShadowEvent(final PlatformEvent event, final ShadowEvent selfParent, final ShadowEvent otherParent) {
        super(event, Stream.of(selfParent, otherParent).filter(Objects::nonNull).toList());
    }

    /**
     * Construct a shadow event from an event
     *
     * @param event
     * 		the event
     */
    public ShadowEvent(final PlatformEvent event) {
        this(event, null, null);
    }

}
