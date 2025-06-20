// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

/**
 * Describes various status that the event creator may be in.
 */
public enum EventCreationStatus {
    /**
     * The event creator is in the process of attempting to create an event. This may or may not be successful.
     */
    ATTEMPTING_CREATION,
    /**
     * Events are not currently being created because there are currently no events to serve as parents.
     */
    NO_ELIGIBLE_PARENTS,
    /**
     * Events are not currently being created because of the event creation rate limit.
     */
    RATE_LIMITED,
    /**
     * Events can't currently be created due to backpressure preventing the most recent event from being submitted to
     * the intake pipeline.
     */
    PIPELINE_INSERTION,
    /**
     * Event creation is not permitted by the current platform status.
     */
    PLATFORM_STATUS,
    /**
     * Event creation is not permitted because this node is currently overloaded and is not keeping up with the required
     * work load.
     */
    OVERLOADED,
    /**
     * Event creation has not yet been started.
     */
    IDLE
}
