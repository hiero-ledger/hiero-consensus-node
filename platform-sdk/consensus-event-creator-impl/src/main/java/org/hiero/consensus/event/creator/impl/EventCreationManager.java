// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Creates and signs events. Will sometimes decide not to create new events based on external rules.
 */
public interface EventCreationManager {

    /**
     * Attempt to create an event.
     *
     * @return the created event, or null if no event was created
     */
    @Nullable
    PlatformEvent maybeCreateEvent();

    /**
     * Register a new event from event intake.
     *
     * @param event the event to add
     */
    void registerEvent(@NonNull PlatformEvent event);

    /**
     * Update the event window, defining the minimum threshold for an event to be non-ancient.
     *
     * @param eventWindow the event window
     */
    void setEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Update the platform status.
     *
     * @param platformStatus the new platform status
     */
    void updatePlatformStatus(@NonNull PlatformStatus platformStatus);

    /**
     * Report the amount of time that the system has been in an unhealthy state. Will receive a report of
     * {@link Duration#ZERO} when the system enters a healthy state.
     *
     * @param duration the amount of time that the system has been in an unhealthy state
     */
    void reportUnhealthyDuration(@NonNull final Duration duration);

    /**
     * Report the current sync information against specific peer; EventCreator can use information inside to compute the
     * round lag or any other information it needs to control event creation
     *
     * @param syncProgress status of sync in progress
     */
    void reportSyncProgress(@NonNull SyncProgress syncProgress);

    /**
     * Set the quiescence state of this event creator. The event creator will always behave according to the most recent
     * quiescence command that it has been given.
     *
     * @param quiescenceCommand the quiescence command
     */
    void quiescenceCommand(@NonNull QuiescenceCommand quiescenceCommand);

    /**
     * Clear the internal state of the event creation manager.
     */
    void clear();
}
