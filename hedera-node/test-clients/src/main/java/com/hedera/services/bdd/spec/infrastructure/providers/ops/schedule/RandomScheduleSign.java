// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SOME_SIGNATURES_WERE_INVALID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import java.util.Optional;

public class RandomScheduleSign implements OpProvider {

    public static final int DEFAULT_CEILING_NUM = 10_000;

    private final RegistrySourcedNameProvider<ScheduleID> schedules;
    private final RegistrySourcedNameProvider<AccountID> accounts;

    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(SOME_SIGNATURES_WERE_INVALID, INVALID_SCHEDULE_ID, SCHEDULE_ALREADY_EXECUTED);

    public RandomScheduleSign(
            RegistrySourcedNameProvider<ScheduleID> schedules, RegistrySourcedNameProvider<AccountID> accounts) {
        this.schedules = schedules;
        this.accounts = accounts;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        var schedulesQualifying = schedules.getQualifying();
        if (schedulesQualifying.isEmpty()) {
            return Optional.empty();
        }

        var account = accounts.getQualifying();
        if (account.isEmpty()) {
            return Optional.empty();
        }

        var op = scheduleSign(schedulesQualifying.get())
                .logged()
                .alsoSigningWith(account.get())
                .hasAnyPrecheck()
                .hasKnownStatusFrom(permissibleOutcomes);
        return Optional.of(op);
    }
}
