// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.ADMIN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.VALID_SCHEDULED_TXN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class ScheduleDeleteTest {

    @HapiTest
    final Stream<DynamicTest> deleteWithNoAdminKeyFails() {
        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))),
                scheduleDelete(VALID_SCHEDULED_TXN).hasKnownStatus(SCHEDULE_IS_IMMUTABLE));
    }

    @HapiTest
    final Stream<DynamicTest> unauthorizedDeletionFails() {
        return hapiTest(
                newKeyNamed(ADMIN),
                newKeyNamed("non-admin-key"),
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .adminKey(ADMIN),
                scheduleDelete(VALID_SCHEDULED_TXN)
                        .signedBy(DEFAULT_PAYER, "non-admin-key")
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> deletingAlreadyDeletedIsObvious() {
        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                newKeyNamed(ADMIN),
                scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .adminKey(ADMIN),
                scheduleDelete(VALID_SCHEDULED_TXN).signedBy(ADMIN, DEFAULT_PAYER),
                scheduleDelete(VALID_SCHEDULED_TXN)
                        .fee(ONE_HBAR)
                        .signedBy(ADMIN, DEFAULT_PAYER)
                        .hasKnownStatus(SCHEDULE_ALREADY_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(SENDER),
                scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, FUNDING, 1)))
                        .adminKey(ADMIN),
                submitModified(withSuccessivelyVariedBodyIds(), () -> scheduleDelete(VALID_SCHEDULED_TXN)
                        .signedBy(DEFAULT_PAYER, ADMIN)));
    }

    @HapiTest
    final Stream<DynamicTest> getScheduleInfoIdVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(SENDER),
                scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, FUNDING, 1)))
                        .adminKey(ADMIN),
                sendModified(withSuccessivelyVariedQueryIds(), () -> getScheduleInfo(VALID_SCHEDULED_TXN)));
    }

    @HapiTest
    final Stream<DynamicTest> deletingNonExistingFails() {
        return hapiTest(
                scheduleDelete("0.0.534").fee(ONE_HBAR).hasKnownStatus(INVALID_SCHEDULE_ID),
                scheduleDelete("0.0.0").fee(ONE_HBAR).hasKnownStatus(INVALID_SCHEDULE_ID));
    }

    @HapiTest
    final Stream<DynamicTest> deletingExecutedIsPointless() {
        return hapiTest(
                createTopic("ofGreatInterest"),
                newKeyNamed(ADMIN),
                scheduleCreate(VALID_SCHEDULED_TXN, submitMessageTo("ofGreatInterest"))
                        .adminKey(ADMIN),
                scheduleDelete(VALID_SCHEDULED_TXN)
                        .signedBy(ADMIN, DEFAULT_PAYER)
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED));
    }
}
