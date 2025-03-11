// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import java.util.Optional;

public class RandomScheduleDeletion implements OpProvider {
    private final RegistrySourcedNameProvider<ScheduleID> schedules;
    private final RegistrySourcedNameProvider<AccountID> accounts;

    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(SCHEDULE_IS_IMMUTABLE, INVALID_SCHEDULE_ID, SCHEDULE_ALREADY_EXECUTED);

    public RandomScheduleDeletion(
            RegistrySourcedNameProvider<ScheduleID> schedules, RegistrySourcedNameProvider<AccountID> accounts) {
        this.schedules = schedules;
        this.accounts = accounts;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        var target = schedules.getQualifying();
        var account = accounts.getQualifying();
        if (target.isEmpty() || account.isEmpty()) {
            return Optional.empty();
        }

        var op = scheduleDelete(target.get())
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .hasKnownStatusFrom(permissibleOutcomes)
                .payingWith(account.get())
                .signedBy(account.get());
        return Optional.of(op);
    }
}
