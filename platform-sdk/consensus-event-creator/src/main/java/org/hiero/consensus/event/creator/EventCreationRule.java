// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An object used to limit or prevent the creation of new events.
 */
public interface EventCreationRule {

    /**
     * Check if event creation is currently permitted.
     *
     * @return true if event creation is permitted, false otherwise
     */
    boolean isEventCreationPermitted();

    /**
     * This method is called whenever an event is created.
     */
    void eventWasCreated();

    /**
     * If event creation is blocked by this rule, this method should return the status that the event creator should
     * adopt.
     *
     * @return the status that the event creator should adopt if this rule blocks event creation
     */
    @NonNull
    EventCreationStatus getEventCreationStatus();
}
