// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
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
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.handleAnyRepeatableQueryPayment;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.OTHER_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_UPDATE;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class ScheduleServiceFeesSuite {
    private static final double BASE_FEE_SCHEDULE_CREATE = 0.01;
    private static final double BASE_FEE_SCHEDULE_SIGN = 0.001;
    private static final double BASE_FEE_SCHEDULE_DELETE = 0.001;
    private static final double BASE_FEE_SCHEDULE_INFO = 0.0001;
    private static final double BASE_FEE_CONTRACT_CALL = 0.1;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "scheduling.whitelist", "ContractCall,CryptoCreate,CryptoTransfer,FileDelete,FileUpdate,SystemDelete"));
    }

    @LeakyRepeatableHapiTest(value = NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, fees = "scheduled-contract-fees.json")
    @DisplayName("Schedule ops have expected USD fees")
    final Stream<DynamicTest> scheduleOpsBaseUSDFees() {
        final String SCHEDULE_NAME = "canonical";
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                uploadInitCode(SIMPLE_UPDATE),
                cryptoCreate(OTHER_PAYER),
                cryptoCreate(PAYING_SENDER),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                contractCreate(SIMPLE_UPDATE).gas(300_000L),
                scheduleCreate(
                                SCHEDULE_NAME,
                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                        .memo("")
                                        .fee(ONE_HBAR))
                        .payingWith(OTHER_PAYER)
                        .via("canonicalCreation")
                        .alsoSigningWith(PAYING_SENDER)
                        .adminKey(OTHER_PAYER),
                scheduleSign(SCHEDULE_NAME)
                        .via("canonicalSigning")
                        .payingWith(PAYING_SENDER)
                        .alsoSigningWith(RECEIVER),
                scheduleCreate(
                                "tbd",
                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                        .memo("")
                                        .fee(ONE_HBAR))
                        .payingWith(PAYING_SENDER)
                        .adminKey(PAYING_SENDER),
                scheduleDelete("tbd").via("canonicalDeletion").payingWith(PAYING_SENDER),
                scheduleCreate(
                                "contractCall",
                                contractCall(SIMPLE_UPDATE, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                        .gas(24_000)
                                        .memo("")
                                        .fee(ONE_HBAR))
                        .payingWith(OTHER_PAYER)
                        .via("canonicalContractCall")
                        .adminKey(OTHER_PAYER),
                getScheduleInfo(SCHEDULE_NAME)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("getScheduleInfoBasic"),
                handleAnyRepeatableQueryPayment(),
                validateChargedUsdWithin("canonicalCreation", BASE_FEE_SCHEDULE_CREATE, 3.0),
                validateChargedUsdWithin("canonicalSigning", BASE_FEE_SCHEDULE_SIGN, 3.0),
                validateChargedUsdWithin("canonicalDeletion", BASE_FEE_SCHEDULE_DELETE, 3.0),
                validateChargedUsdWithin("canonicalContractCall", BASE_FEE_CONTRACT_CALL, 3.0),
                validateChargedUsd("getScheduleInfoBasic", BASE_FEE_SCHEDULE_INFO));
    }
}
