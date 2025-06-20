// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Schedules.
 * There are three ways to look up schedules.  A single schedule by {@link ScheduleID}, a list
 * of schedules by "equality" (a hash comparison for efficiency), and a list of schedules by
 * "expiration second" (for quickly finding expired schedules to remove).
 */
public interface ReadableScheduleStore {

    /**
     * Gets the schedule with the given {@link ScheduleID}. If there is no schedule with given ID
     * returns null.  We do not use {@link Optional} here because null is not a valid value, so
     * there is no need to distinguish between empty and null.
     *
     * @param id given id for the schedule
     *
     * @return the schedule with the given id
     */
    @Nullable
    Schedule get(@Nullable ScheduleID id);

    /**
     * Get a scheduleID that its Schedule is "hash equal" to the provided Schedule.
     * Two schedules are "hash equal" if the hash of the original create transaction for one schedule
     * is identical to the hash of the create transaction for the other schedule.  This is primarily
     * used to ensure that we do not permit duplicate schedules to be created. Note that hash equality
     * is necessary, but not sufficient, so the return values must be compared field-by-field to ensure
     * actual equality (within the constraints of schedule duplication) before asserting that the requested
     * schedule is a duplicate.
     * @param scheduleToMatch a {@link Schedule} to match according to hash
     * @return a {@code List<Schedule>} of entries that have the same hash as the provided schedule
     */
    @Nullable
    ScheduleID getByEquality(@NonNull Schedule scheduleToMatch);

    /**
     * Gets the id of the transaction scheduled to happen at the given order, if it exists.
     * @param scheduledOrder the order of the transaction
     * @return the id of the transaction scheduled to happen at the given order
     */
    @Nullable
    ScheduleID getByOrder(@NonNull ScheduledOrder scheduledOrder);

    /**
     * Returns the number of schedules in state, for use in enforcing creation limits.
     *
     * @return the number of schedules in state
     */
    long numSchedulesInState();

    /**
     * Returns the number of schedules that are scheduled to execute at the given consensus second.
     * @param consensusSecond the consensus second to check for scheduled transactions
     * @return the number of schedules that are scheduled to execute at the given consensus second
     */
    int numTransactionsScheduledAt(long consensusSecond);

    /**
     * Returns the scheduled transaction counts at the given consensus second, if any exist.
     * @param consensusSecond the consensus second to check for scheduled transactions
     * @return the scheduled transaction counts at the given consensus second
     */
    @Nullable
    ScheduledCounts scheduledCountsAt(long consensusSecond);

    /**
     * If the given consensus second has any scheduled transactions, returns a snapshot of the throttle
     * usage for those transactions within that second. The throttles are implicit in the combination of
     * the network throttle definitions and the fraction of network capacity that is allowed to be
     * scheduled to execute in a single second.
     * @param consensusSecond the consensus second to check for scheduling usage
     * @return null or a usage snapshot for the transactions scheduled at the given consensus second
     */
    @Nullable
    ThrottleUsageSnapshots usageSnapshotsForScheduled(long consensusSecond);
}
