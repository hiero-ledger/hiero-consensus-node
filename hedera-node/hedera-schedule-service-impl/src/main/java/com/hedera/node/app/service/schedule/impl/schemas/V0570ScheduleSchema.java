// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EQUALITY_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EXPIRY_SEC_STATE_ID;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * General schema for the schedule service.
 */
public final class V0570ScheduleSchema extends Schema<SemanticVersion> {

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

    public static final int SCHEDULED_COUNTS_STATE_ID =
            StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULED_COUNTS.protoOrdinal();

    public static final String SCHEDULED_COUNTS_STATE_LABEL = computeLabel(ScheduleService.NAME, SCHEDULED_COUNTS_KEY);

    /**
     * The state key of a map from an order position within a consensus second to the id of
     * the transaction scheduled to executed in that order within that second.
     */
    public static final String SCHEDULED_ORDERS_KEY = "SCHEDULED_ORDERS";

    public static final int SCHEDULED_ORDERS_STATE_ID =
            StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULED_ORDERS.protoOrdinal();

    public static final String SCHEDULED_ORDERS_STATE_LABEL = computeLabel(ScheduleService.NAME, SCHEDULED_ORDERS_KEY);

    /**
     * The state key of a map from consensus second to the throttle utilization of transactions
     * scheduled so far in that second.
     */
    public static final String SCHEDULED_USAGES_KEY = "SCHEDULED_USAGES";

    public static final int SCHEDULED_USAGES_STATE_ID =
            StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULED_USAGES.protoOrdinal();

    public static final String SCHEDULED_USAGES_STATE_LABEL = computeLabel(ScheduleService.NAME, SCHEDULED_USAGES_KEY);

    /**
     * The state key of a map from a hash of the schedule's equality values to its schedule id.
     */
    public static final String SCHEDULE_ID_BY_EQUALITY_KEY = "SCHEDULE_ID_BY_EQUALITY";

    public static final int SCHEDULE_ID_BY_EQUALITY_STATE_ID =
            StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULE_ID_BY_EQUALITY.protoOrdinal();

    public static final String SCHEDULE_ID_BY_EQUALITY_STATE_LABEL =
            computeLabel(ScheduleService.NAME, SCHEDULE_ID_BY_EQUALITY_KEY);

    /**
     * Instantiates a new V0570 (version 0.57.0) schedule schema.
     */
    public V0570ScheduleSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(scheduleIdByEquality(), scheduledOrders(), scheduledCounts(), scheduledUsages());
    }

    @Override
    public @NonNull Set<Integer> statesToRemove() {
        return Set.of(SCHEDULES_BY_EXPIRY_SEC_STATE_ID, SCHEDULES_BY_EQUALITY_STATE_ID);
    }

    private static StateDefinition<TimestampSeconds, ScheduledCounts> scheduledCounts() {
        return StateDefinition.onDisk(
                SCHEDULED_COUNTS_STATE_ID,
                SCHEDULED_COUNTS_KEY,
                TimestampSeconds.PROTOBUF,
                ScheduledCounts.PROTOBUF,
                MAX_SCHEDULED_COUNTS);
    }

    private static StateDefinition<ScheduledOrder, ScheduleID> scheduledOrders() {
        return StateDefinition.onDisk(
                SCHEDULED_ORDERS_STATE_ID,
                SCHEDULED_ORDERS_KEY,
                ScheduledOrder.PROTOBUF,
                ScheduleID.PROTOBUF,
                MAX_SCHEDULED_ORDERS);
    }

    private static StateDefinition<TimestampSeconds, ThrottleUsageSnapshots> scheduledUsages() {
        return StateDefinition.onDisk(
                SCHEDULED_USAGES_STATE_ID,
                SCHEDULED_USAGES_KEY,
                TimestampSeconds.PROTOBUF,
                ThrottleUsageSnapshots.PROTOBUF,
                MAX_SCHEDULED_USAGES);
    }

    private static StateDefinition<ProtoBytes, ScheduleID> scheduleIdByEquality() {
        return StateDefinition.onDisk(
                SCHEDULE_ID_BY_EQUALITY_STATE_ID,
                SCHEDULE_ID_BY_EQUALITY_KEY,
                ProtoBytes.PROTOBUF,
                ScheduleID.PROTOBUF,
                MAX_SCHEDULE_ID_BY_EQUALITY);
    }
}
