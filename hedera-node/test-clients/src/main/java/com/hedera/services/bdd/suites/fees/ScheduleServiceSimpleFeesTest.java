// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.OTHER_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_UPDATE;

import com.hedera.services.bdd.junit.HapiTest;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
public class ScheduleServiceSimpleFeesTest {
    private static final double BASE_FEE_SCHEDULE_CREATE = 0.01;
    private static final double BASE_FEE_SCHEDULE_SIGN = 0.001;
    private static final double BASE_FEE_SCHEDULE_DELETE = 0.001;
    private static final double BASE_FEE_SCHEDULE_INFO = 0.0001;
    private static final double BASE_FEE_CONTRACT_CALL = 0.1;

    @HapiTest
    @DisplayName("Schedule ops have expected USD fees")
    final Stream<DynamicTest> scheduleOpsBaseUSDFees() {
        final String SCHEDULE_NAME = "canonical";
        return hapiTest(
                uploadInitCode(SIMPLE_UPDATE),
                cryptoCreate(OTHER_PAYER),
                cryptoCreate(PAYING_SENDER),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                contractCreate(SIMPLE_UPDATE).gas(300_000L),
                scheduleCreate(
                                SCHEDULE_NAME,
                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                        .blankMemo()
                                        .fee(ONE_HBAR))
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("canonicalCreation")
                        .fee(ONE_HBAR),
                scheduleSign(SCHEDULE_NAME)
                        .fee(ONE_HBAR)
                        .via("canonicalSigning")
                        .payingWith(PAYING_SENDER)
                        .signedBy(PAYING_SENDER),
                scheduleSign(SCHEDULE_NAME)
                        .fee(ONE_HBAR)
                        .via("multiScheduleSign")
                        .payingWith(PAYING_SENDER)
                        .signedBy(RECEIVER, PAYING_SENDER),
                scheduleCreate(
                                "tbd",
                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                        .fee(ONE_HBAR))
                        .fee(ONE_HBAR)
                        .payingWith(PAYING_SENDER)
                        .adminKey(PAYING_SENDER),
                scheduleDelete("tbd")
                        .via("canonicalDeletion")
                        .payingWith(PAYING_SENDER)
                        .signedBy(PAYING_SENDER)
                        .fee(ONE_HBAR),
                scheduleCreate(
                                "contractCall",
                                contractCall(SIMPLE_UPDATE, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                        .gas(24_000)
                                        .fee(ONE_HBAR))
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .fee(ONE_HBAR)
                        .via("canonicalContractCall"),
                getScheduleInfo(SCHEDULE_NAME)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("getScheduleInfoBasic"),
                validateChargedUsd("canonicalCreation", BASE_FEE_SCHEDULE_CREATE),
                validateChargedUsd("canonicalSigning", BASE_FEE_SCHEDULE_SIGN),
                // validate the fee when we have single overage signature
                validateChargedUsd("multiScheduleSign", BASE_FEE_SCHEDULE_SIGN + SIGNATURE_FEE_AFTER_MULTIPLIER),
                validateChargedUsd("canonicalDeletion", BASE_FEE_SCHEDULE_DELETE),
                validateChargedUsd("canonicalContractCall", BASE_FEE_CONTRACT_CALL),
                validateChargedUsd("getScheduleInfoBasic", BASE_FEE_SCHEDULE_INFO));
    }
}
