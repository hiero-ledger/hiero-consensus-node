// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import org.hiero.consensus.model.crypto.Hash;
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
public class ShadowEvent {
    /**
     * the real event
     */
    private final PlatformEvent event;

    /**
     * self-parent
     */
    private ShadowEvent selfParent;

    /**
     * other-parent
     */
    private ShadowEvent otherParent;

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
        this.event = event;
        this.selfParent = selfParent;
        this.otherParent = otherParent;
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

    /**
     * Get the self-parent of {@code this} shadow event
     *
     * @return the self-parent of {@code this} shadow event
     */
    public ShadowEvent getSelfParent() {
        return this.selfParent;
    }

    /**
     * Get the other-parent of {@code this} shadow event
     *
     * @return the other-parent of {@code this} shadow event
     */
    public ShadowEvent getOtherParent() {
        return this.otherParent;
    }

    /**
     * Get the hashgraph event references by this shadow event
     *
     * @return the hashgraph event references by this shadow event
     */
    public PlatformEvent getEvent() {
        return event;
    }

    /**
     * The cryptographic hash of an event shadow is the cryptographic hash of the event base
     *
     * @return The cryptographic base hash of an event.
     */
    public Hash getEventBaseHash() {
        return event.getHash();
    }

    /**
     * Disconnect this shadow event from its parents. Remove inbound links and outbound links
     */
    public void disconnect() {
        selfParent = null;
        otherParent = null;
    }

    /**
     * Two shadow events are equal iff their reference hashgraph events are equal.
     *
     * @return true iff {@code this} and {@code o} reference hashgraph events that compare equal
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ShadowEvent)) {
            return false;
        }

        final ShadowEvent s = (ShadowEvent) o;

        return getEventBaseHash().equals(s.getEventBaseHash());
    }

    /**
     * The hash code of a shadow event is the Swirlds cryptographic base hash of the hashgraph event which this shadow
     * event references.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return getEventBaseHash().hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return getEvent().toString();
    }
}
