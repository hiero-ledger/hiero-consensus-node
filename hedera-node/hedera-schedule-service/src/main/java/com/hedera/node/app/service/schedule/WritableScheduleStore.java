// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * The interface Writable schedule store.
 */
public interface WritableScheduleStore extends ReadableScheduleStore {

    /**
     * Delete a given schedule from this state.
     * Given the ID of a schedule and a consensus time, delete that ID from
     * this state as of the consensus time {@link Instant} provided.
     *
     * @param scheduleToDelete The ID of a schedule to be deleted.
     * @param consensusTime The current consensus time
     * @return the schedule
     * @throws IllegalStateException if the {@link ScheduleID} to be deleted is not present in this state, or the ID
     *     value has a mismatched realm or shard for this node.
     */
    Schedule delete(@Nullable ScheduleID scheduleToDelete, @NonNull Instant consensusTime);

    /**
     * Add a schedule to this state. If the schedule already exists it will be replaced.
     *
     * @param scheduleToAdd The schedule to add
     */
    void put(@NonNull Schedule scheduleToAdd);

    /**
     * Adds a new schedule to the store. This will also increment the entity counts for schedules.
     * @param scheduleToAdd The schedule to add to the store
     */
    void putAndIncrementCount(@NonNull Schedule scheduleToAdd);

    /**
     * Purges all schedule state associated with the given order.
     * @param order The order to purge schedules for.
     * @return whether this was the last scheduled order in its consensus second
     */
    boolean purgeByOrder(@NonNull ScheduledOrder order);

    /**
     * Updates the usage of the throttles for the given consensus second.
     * @param consensusSecond The consensus second to track the usage for.
     * @param usageSnapshots The usage snapshots to track.
     */
    void trackUsage(long consensusSecond, @NonNull ThrottleUsageSnapshots usageSnapshots);

    /**
     * Purges expired schedules from the store.
     *
     * @param firstSecondToExpire The consensus second of the first schedule to expire.
     * @param lastSecondToExpire  The consensus second of the last schedule to expire.
     */
    void purgeExpiredRangeClosed(long firstSecondToExpire, long lastSecondToExpire);
}
