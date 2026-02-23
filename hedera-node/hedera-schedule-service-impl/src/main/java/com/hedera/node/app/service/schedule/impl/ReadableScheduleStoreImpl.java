// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static com.hedera.node.app.service.schedule.impl.ScheduleStoreUtility.calculateBytesHash;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.ReadableEntityCounters;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema;
import com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Schedules.
 *
 */
public class ReadableScheduleStoreImpl implements ReadableScheduleStore {

    private static final String NULL_STATE_IN_CONSTRUCTOR_MESSAGE =
            "Null states instance passed to ReadableScheduleStore constructor, possible state corruption.";

    private final ReadableKVState<ScheduleID, Schedule> schedulesById;
    private final ReadableKVState<TimestampSeconds, ScheduledCounts> scheduledCounts;
    private final ReadableKVState<TimestampSeconds, ThrottleUsageSnapshots> scheduledUsages;
    private final ReadableKVState<ScheduledOrder, ScheduleID> scheduledOrders;
    private final ReadableKVState<ProtoBytes, ScheduleID> scheduleIdByStringHash;
    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableScheduleStore} instance.
     *
     * @param states The state to use.
     */
    public ReadableScheduleStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states, NULL_STATE_IN_CONSTRUCTOR_MESSAGE);
        this.entityCounters = requireNonNull(entityCounters);
        schedulesById = states.get(V0490ScheduleSchema.SCHEDULES_BY_ID_STATE_ID);
        scheduledCounts = states.get(V0570ScheduleSchema.SCHEDULED_COUNTS_STATE_ID);
        scheduledOrders = states.get(V0570ScheduleSchema.SCHEDULED_ORDERS_STATE_ID);
        scheduledUsages = states.get(V0570ScheduleSchema.SCHEDULED_USAGES_STATE_ID);
        scheduleIdByStringHash = states.get(V0570ScheduleSchema.SCHEDULE_ID_BY_EQUALITY_STATE_ID);
    }

    /**
     * Get a {@link Schedule} referenced by {@link ScheduleID}.
     * If the schedule ID is null or there is no schedule referenced by that ID, then return null.
     *
     * @param id given id for the schedule
     *
     * @return The Schedule corresponding to the id provided, or null if there is no such schedule
     */
    @Override
    @Nullable
    public Schedule get(@Nullable final ScheduleID id) {
        return (id == null) ? null : schedulesById.get(id);
    }

    @Override
    @Nullable
    public ScheduleID getByEquality(@NonNull final Schedule schedule) {
        requireNonNull(schedule);
        final var bytesHash = calculateBytesHash(schedule);
        return scheduleIdByStringHash.get(new ProtoBytes(bytesHash));
    }

    @Nullable
    @Override
    public ScheduleID getByOrder(@NonNull final ScheduledOrder scheduledOrder) {
        requireNonNull(scheduledOrder);
        return scheduledOrders.get(scheduledOrder);
    }

    @Override
    public long numSchedulesInState() {
        return entityCounters.getCounterFor(EntityType.SCHEDULE);
    }

    @Override
    public int numTransactionsScheduledAt(final long consensusSecond) {
        final var counts = scheduledCounts.get(new TimestampSeconds(consensusSecond));
        return counts == null ? 0 : counts.numberScheduled();
    }

    @Nullable
    @Override
    public ScheduledCounts scheduledCountsAt(long consensusSecond) {
        return scheduledCounts.get(new TimestampSeconds(consensusSecond));
    }

    @Override
    public @Nullable ThrottleUsageSnapshots usageSnapshotsForScheduled(final long consensusSecond) {
        return scheduledUsages.get(new TimestampSeconds(consensusSecond));
    }
}
