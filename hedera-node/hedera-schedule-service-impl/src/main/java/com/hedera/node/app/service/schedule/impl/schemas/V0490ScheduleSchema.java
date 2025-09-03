// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.schemas;

import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduleList;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * General schema for the schedule service.
 */
public final class V0490ScheduleSchema extends Schema {

    private static final long MAX_SCHEDULES_BY_ID_KEY = 50_000L;
    private static final long MAX_SCHEDULES_BY_EXPIRY_SEC_KEY = 50_000L;
    private static final long MAX_SCHEDULES_BY_EQUALITY = 50_000L;
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public static final String SCHEDULES_BY_ID_KEY = "SCHEDULES_BY_ID";
    public static final int SCHEDULES_BY_ID_STATE_ID =
            StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULES_BY_ID.protoOrdinal();
    public static final String SCHEDULES_BY_ID_STATE_LABEL = computeLabel(ScheduleService.NAME, SCHEDULES_BY_ID_KEY);

    public static final String SCHEDULES_BY_EXPIRY_SEC_KEY = "SCHEDULES_BY_EXPIRY_SEC";
    public static final int SCHEDULES_BY_EXPIRY_SEC_STATE_ID =
            StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULES_BY_EXPIRY_SEC.protoOrdinal();
    public static final String SCHEDULES_BY_EXPIRY_SEC_STATE_LABEL =
            computeLabel(ScheduleService.NAME, SCHEDULES_BY_EXPIRY_SEC_KEY);

    public static final String SCHEDULES_BY_EQUALITY_KEY = "SCHEDULES_BY_EQUALITY";
    public static final int SCHEDULES_BY_EQUALITY_STATE_ID =
            StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULES_BY_EQUALITY.protoOrdinal();
    public static final String SCHEDULES_BY_EQUALITY_STATE_LABEL =
            computeLabel(ScheduleService.NAME, SCHEDULES_BY_EQUALITY_KEY);

    /**
     * Instantiates a new V0490 (version 0.49.0) schedule schema.
     */
    public V0490ScheduleSchema() {
        super(VERSION);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(schedulesByIdDef(), schedulesByExpirySec(), schedulesByEquality());
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        // There are no scheduled transactions at genesis
    }

    private static StateDefinition<ScheduleID, Schedule> schedulesByIdDef() {
        return StateDefinition.onDisk(
                SCHEDULES_BY_ID_STATE_ID,
                SCHEDULES_BY_ID_KEY,
                ScheduleID.PROTOBUF,
                Schedule.PROTOBUF,
                MAX_SCHEDULES_BY_ID_KEY);
    }

    private static StateDefinition<ProtoLong, ScheduleList> schedulesByExpirySec() {
        return StateDefinition.onDisk(
                SCHEDULES_BY_EXPIRY_SEC_STATE_ID,
                SCHEDULES_BY_EXPIRY_SEC_KEY,
                ProtoLong.PROTOBUF,
                ScheduleList.PROTOBUF,
                MAX_SCHEDULES_BY_EXPIRY_SEC_KEY);
    }

    private static StateDefinition<ProtoBytes, ScheduleList> schedulesByEquality() {
        return StateDefinition.onDisk(
                SCHEDULES_BY_EQUALITY_STATE_ID,
                SCHEDULES_BY_EQUALITY_KEY,
                ProtoBytes.PROTOBUF,
                ScheduleList.PROTOBUF,
                MAX_SCHEDULES_BY_EQUALITY);
    }
}
