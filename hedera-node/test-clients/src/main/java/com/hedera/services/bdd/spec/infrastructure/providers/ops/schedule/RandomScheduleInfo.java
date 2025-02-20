// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import java.util.Optional;

public class RandomScheduleInfo implements OpProvider {
    private final RegistrySourcedNameProvider<ScheduleID> schedules;

    private final ResponseCodeEnum[] permissibleCostAnswerPrechecks = standardQueryPrechecksAnd(INVALID_SCHEDULE_ID);
    private final ResponseCodeEnum[] permissibleAnswerOnlyPrechecks = standardQueryPrechecksAnd(INVALID_SCHEDULE_ID);

    public RandomScheduleInfo(RegistrySourcedNameProvider<ScheduleID> schedules) {
        this.schedules = schedules;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        Optional<String> schedule = schedules.getQualifying();
        if (schedule.isEmpty()) {
            return Optional.empty();
        }

        var op = getScheduleInfo(schedule.get())
                .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks)
                .hasAnswerOnlyPrecheckFrom(permissibleAnswerOnlyPrechecks);

        return Optional.of(op);
    }
}
