// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.CRYPTO_TRANSFER_RECEIVER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class RandomSchedule implements OpProvider {
    private final AtomicInteger opNo = new AtomicInteger();
    private final RegistrySourcedNameProvider<ScheduleID> schedules;
    private final EntityNameProvider accounts;

    public final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(UNRESOLVABLE_REQUIRED_SIGNERS, IDENTICAL_SCHEDULE_ALREADY_CREATED);

    public static final int DEFAULT_CEILING_NUM = 100;
    private int ceilingNum = DEFAULT_CEILING_NUM;
    static final String ADMIN_KEY = DEFAULT_PAYER;

    public RandomSchedule(RegistrySourcedNameProvider<ScheduleID> schedules, EntityNameProvider accounts) {
        this.schedules = schedules;
        this.accounts = accounts;
    }

    public RandomSchedule ceiling(int n) {
        ceilingNum = n;
        return this;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        if (schedules.numPresent() >= ceilingNum) {
            return Optional.empty();
        }
        final var from = accounts.getQualifying();
        if (from.isEmpty()) {
            return Optional.empty();
        }
        int id = opNo.getAndIncrement();

        var op = scheduleCreate(
                        "schedule" + id,
                        cryptoTransfer(tinyBarsFromTo(from.get(), CRYPTO_TRANSFER_RECEIVER, 1))
                                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS))
                .alsoSigningWith(from.get())
                .fee(ONE_HUNDRED_HBARS)
                .memo("randomlycreated" + id)
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .hasKnownStatusFrom(permissibleOutcomes)
                .adminKey(ADMIN_KEY);
        return Optional.of(op);
    }
}
