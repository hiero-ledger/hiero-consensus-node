// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Wraps an {@link EventCreator} and provides additional functionality. Will sometimes decide not to create new events
 * based on external rules. Forwards created events to a consumer, and retries forwarding if the consumer is not
 * immediately able to accept the event.
 */
public interface EventCreationManager {
    /**
     * Attempt to create an event.
     *
     * @return the created event, or null if no event was created
     */
    @InputWireLabel("heartbeat")
    @Nullable
    PlatformEvent maybeCreateEvent();

    /**
     * Register a new event from event intake.
     *
     * @param event the event to add
     */
    @InputWireLabel("PlatformEvent")
    void registerEvent(@NonNull PlatformEvent event);

    /**
     * Update the event window, defining the minimum threshold for an event to be non-ancient.
     *
     * @param eventWindow the event window
     */
    @InputWireLabel("event window")
    void setEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Update the platform status.
     *
     * @param platformStatus the new platform status
     */
    @InputWireLabel("PlatformStatus")
    void updatePlatformStatus(@NonNull PlatformStatus platformStatus);

    /**
     * Report the amount of time that the system has been in an unhealthy state. Will receive a report of
     * {@link Duration#ZERO} when the system enters a healthy state.
     *
     * @param duration the amount of time that the system has been in an unhealthy state
     */
    @InputWireLabel("health info")
    void reportUnhealthyDuration(@NonNull final Duration duration);

    @InputWireLabel("sync round lag")
    void reportSyncRoundLag(@NonNull Double lag);

    /**
     * Clear the internal state of the event creation manager.
     */
    @InputWireLabel("clear")
    void clear();
}
