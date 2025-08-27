// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.schemas;

import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EQUALITY_KEY;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EXPIRY_SEC_KEY;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * General schema for the schedule service.
 */
public final class V0570ScheduleSchema extends Schema {
    private static final Logger log = LogManager.getLogger(V0570ScheduleSchema.class);

    private static final long MAX_SCHEDULED_COUNTS = 50_000L;
    private static final long MAX_SCHEDULED_ORDERS = 50_000L;
    private static final long MAX_SCHEDULED_USAGES = 50_000L;
    private static final long MAX_SCHEDULE_ID_BY_EQUALITY = 50_000L;
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(57).patch(0).build();
    /**
     * The state key of a map from consensus second to the counts of transactions scheduled
     * and processed within that second.
     */
    public static final String SCHEDULED_COUNTS_KEY = "SCHEDULED_COUNTS";
    /**
     * The state key of a map from an order position within a consensus second to the id of
     * the transaction scheduled to executed in that order within that second.
     */
    public static final String SCHEDULED_ORDERS_KEY = "SCHEDULED_ORDERS";
    /**
     * The state key of a map from consensus second to the throttle utilization of transactions
     * scheduled so far in that second.
     */
    public static final String SCHEDULED_USAGES_KEY = "SCHEDULED_USAGES";
    /**
     * The state key of a map from a hash of the schedule's equality values to its schedule id.
     */
    public static final String SCHEDULE_ID_BY_EQUALITY_KEY = "SCHEDULE_ID_BY_EQUALITY";

    /**
     * Instantiates a new V0570 (version 0.57.0) schedule schema.
     */
    public V0570ScheduleSchema() {
        super(VERSION);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(scheduleIdByEquality(), scheduledOrders(), scheduledCounts(), scheduledUsages());
    }

    @Override
    public @NonNull Set<String> statesToRemove() {
        return Set.of(SCHEDULES_BY_EXPIRY_SEC_KEY, SCHEDULES_BY_EQUALITY_KEY);
    }

    private static StateDefinition<TimestampSeconds, ScheduledCounts> scheduledCounts() {
        return StateDefinition.onDisk(
                SCHEDULED_COUNTS_KEY, TimestampSeconds.PROTOBUF, ScheduledCounts.PROTOBUF, MAX_SCHEDULED_COUNTS);
    }

    private static StateDefinition<ScheduledOrder, ScheduleID> scheduledOrders() {
        return StateDefinition.onDisk(
                SCHEDULED_ORDERS_KEY, ScheduledOrder.PROTOBUF, ScheduleID.PROTOBUF, MAX_SCHEDULED_ORDERS);
    }

    private static StateDefinition<TimestampSeconds, ThrottleUsageSnapshots> scheduledUsages() {
        return StateDefinition.onDisk(
                SCHEDULED_USAGES_KEY, TimestampSeconds.PROTOBUF, ThrottleUsageSnapshots.PROTOBUF, MAX_SCHEDULED_USAGES);
    }

    private static StateDefinition<ProtoBytes, ScheduleID> scheduleIdByEquality() {
        return StateDefinition.onDisk(
                SCHEDULE_ID_BY_EQUALITY_KEY, ProtoBytes.PROTOBUF, ScheduleID.PROTOBUF, MAX_SCHEDULE_ID_BY_EQUALITY);
    }
}
