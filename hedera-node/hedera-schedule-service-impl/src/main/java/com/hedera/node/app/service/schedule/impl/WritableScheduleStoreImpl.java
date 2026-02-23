// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema;
import com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A writable store that wraps a writable key-value state and supports operations required to create or update
 * schedule objects as a result of ScheduleCreate, ScheduleSign, or ScheduleDelete transactions.
 */
public class WritableScheduleStoreImpl extends ReadableScheduleStoreImpl implements WritableScheduleStore {

    private static final Logger logger = LogManager.getLogger(WritableScheduleStoreImpl.class);

    private final WritableKVState<ScheduleID, Schedule> schedulesByIdMutable;
    private final WritableKVState<ProtoBytes, ScheduleID> scheduleIdByEqualityMutable;
    private final WritableKVState<TimestampSeconds, ScheduledCounts> scheduleCountsMutable;
    private final WritableKVState<TimestampSeconds, ThrottleUsageSnapshots> scheduleUsagesMutable;
    private final WritableKVState<ScheduledOrder, ScheduleID> scheduleOrdersMutable;

    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableScheduleStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public WritableScheduleStoreImpl(
            @NonNull final WritableStates states, final WritableEntityCounters entityCounters) {
        super(states, entityCounters);

        schedulesByIdMutable = states.get(V0490ScheduleSchema.SCHEDULES_BY_ID_STATE_ID);
        scheduleCountsMutable = states.get(V0570ScheduleSchema.SCHEDULED_COUNTS_STATE_ID);
        scheduleOrdersMutable = states.get(V0570ScheduleSchema.SCHEDULED_ORDERS_STATE_ID);
        scheduleUsagesMutable = states.get(V0570ScheduleSchema.SCHEDULED_USAGES_STATE_ID);
        scheduleIdByEqualityMutable = states.get(V0570ScheduleSchema.SCHEDULE_ID_BY_EQUALITY_STATE_ID);
        this.entityCounters = entityCounters;
    }

    /**
     * Delete a given schedule from this state.
     * Given the ID of a schedule and a consensus time, delete that ID from this state as of the
     * consensus time {@link Instant} provided.
     * @param scheduleId The ID of a schedule to be deleted.
     * @param consensusTime The current consensus time
     * @return the {@link Schedule} marked as deleted
     * @throws IllegalStateException if the {@link ScheduleID} to be deleted is not present in this state
     */
    @Override
    public @NonNull Schedule delete(@Nullable final ScheduleID scheduleId, @NonNull final Instant consensusTime) {
        requireNonNull(consensusTime);
        requireNonNull(scheduleId);
        final var schedule = schedulesByIdMutable.get(scheduleId);
        if (schedule == null) {
            throw new IllegalStateException("Schedule to be deleted, %1$s, not found in state.".formatted(scheduleId));
        }
        final var deletedSchedule = markDeleted(schedule, consensusTime);
        schedulesByIdMutable.put(scheduleId, deletedSchedule);
        return deletedSchedule;
    }

    @Override
    public void put(@NonNull final Schedule schedule) {
        requireNonNull(schedule);
        final var scheduleId = schedule.scheduleIdOrThrow();
        final var extant = schedulesByIdMutable.get(scheduleId);
        schedulesByIdMutable.put(scheduleId, schedule);
        // Updating a schedule that already exists in the store has no other side-effects
        if (extant != null) {
            return;
        }
        final var equalityKey = new ProtoBytes(ScheduleStoreUtility.calculateBytesHash(schedule));
        scheduleIdByEqualityMutable.put(equalityKey, scheduleId);
        final var second = schedule.calculatedExpirationSecond();
        final var countsKey = new TimestampSeconds(second);
        final var oldCounts = scheduleCountsMutable.get(countsKey);
        final var counts = oldCounts == null
                ? new ScheduledCounts(1, 0)
                : new ScheduledCounts(oldCounts.numberScheduled() + 1, oldCounts.numberProcessed());
        scheduleCountsMutable.put(countsKey, counts);
        final var orderKey = new ScheduledOrder(second, counts.numberScheduled() - 1);
        scheduleOrdersMutable.put(orderKey, schedule.scheduleIdOrThrow());
    }

    @Override
    public void putAndIncrementCount(@NonNull final Schedule schedule) {
        put(schedule);
        entityCounters.incrementEntityTypeCount(EntityType.SCHEDULE);
    }

    @Override
    public boolean purgeByOrder(@NonNull final ScheduledOrder order) {
        requireNonNull(order);
        final var scheduleId = getByOrder(order);
        if (scheduleId != null) {
            final var key = new TimestampSeconds(order.expirySecond());
            final var counts = requireNonNull(scheduleCountsMutable.get(key));
            if (order.orderNumber() != counts.numberProcessed()) {
                throw new IllegalStateException("Order %s is not next in counts %s".formatted(order, counts));
            }
            purge(scheduleId);
            scheduleOrdersMutable.remove(order);
            final var newCounts = counts.copyBuilder()
                    .numberProcessed(counts.numberProcessed() + 1)
                    .build();
            if (newCounts.numberProcessed() < newCounts.numberScheduled()) {
                scheduleCountsMutable.put(key, newCounts);
                return false;
            } else {
                scheduleCountsMutable.remove(key);
                scheduleUsagesMutable.remove(key);
            }
        }
        return true;
    }

    @Override
    public void trackUsage(final long consensusSecond, @NonNull final ThrottleUsageSnapshots usageSnapshots) {
        requireNonNull(usageSnapshots);
        scheduleUsagesMutable.put(new TimestampSeconds(consensusSecond), usageSnapshots);
    }

    @Override
    public void purgeExpiredRangeClosed(final long start, final long end) {
        for (long i = start; i <= end; i++) {
            final var countsAndUsagesKey = new TimestampSeconds(i);
            final var counts = scheduleCountsMutable.get(countsAndUsagesKey);
            if (counts != null) {
                for (int j = 0, n = counts.numberScheduled(); j < n; j++) {
                    final var orderKey = new ScheduledOrder(i, j);
                    final var scheduleId = requireNonNull(scheduleOrdersMutable.get(orderKey));
                    purge(scheduleId);
                    scheduleOrdersMutable.remove(orderKey);
                }
                scheduleCountsMutable.remove(countsAndUsagesKey);
                scheduleUsagesMutable.remove(countsAndUsagesKey);
            }
        }
    }

    /**
     * Purge a schedule from the store.
     *
     * @param scheduleId             The ID of the schedule to purge
     */
    private void purge(@NonNull final ScheduleID scheduleId) {
        final var schedule = schedulesByIdMutable.get(scheduleId);
        if (schedule != null) {
            final ProtoBytes hash = new ProtoBytes(ScheduleStoreUtility.calculateBytesHash(schedule));
            scheduleIdByEqualityMutable.remove(hash);
        } else {
            logger.error("Schedule {} not found in state schedulesByIdMutable.", scheduleId);
        }
        schedulesByIdMutable.remove(scheduleId);
        entityCounters.decrementEntityTypeCounter(EntityType.SCHEDULE);
        logger.debug("Purging expired schedule {} from state.", scheduleId);
    }

    @NonNull
    private Schedule markDeleted(final Schedule schedule, final Instant consensusTime) {
        final Timestamp consensusTimestamp = new Timestamp(consensusTime.getEpochSecond(), consensusTime.getNano());
        return new Schedule(
                schedule.scheduleId(),
                true,
                schedule.executed(),
                schedule.waitForExpiry(),
                schedule.memo(),
                schedule.schedulerAccountId(),
                schedule.payerAccountId(),
                schedule.adminKey(),
                schedule.scheduleValidStart(),
                schedule.providedExpirationSecond(),
                schedule.calculatedExpirationSecond(),
                consensusTimestamp,
                schedule.scheduledTransaction(),
                schedule.originalCreateTransaction(),
                schedule.signatories());
    }
}
