// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNodePaymentAmountForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.OTHER_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_UPDATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class ScheduleServiceSimpleFeesTest {
    private static final double BASE_FEE_SCHEDULE_CREATE = 0.01;
    private static final double BASE_FEE_SCHEDULE_SIGN = 0.001;
    private static final double BASE_FEE_SCHEDULE_DELETE = 0.001;
    private static final double BASE_FEE_SCHEDULE_INFO = 0.0001;
    private static final double BASE_FEE_CONTRACT_CALL = 0.1;
    private static final long EXPECTED_NODE_PAYMENT_TINYCENTS = 84L;

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
                validateChargedUsd("getScheduleInfoBasic", BASE_FEE_SCHEDULE_INFO),
                validateNodePaymentAmountForQuery("getScheduleInfoBasic", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("schedule get info - invalid schedule fails - no fee charged")
    final Stream<DynamicTest> scheduleGetInfoInvalidScheduleFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        return hapiTest(
                cryptoCreate(PAYING_SENDER).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(PAYING_SENDER).exposingBalanceTo(initialBalance::set),
                getScheduleInfo("0.0.99999999").payingWith(PAYING_SENDER).hasCostAnswerPrecheck(INVALID_SCHEDULE_ID),
                getAccountBalance(PAYING_SENDER).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }

    @HapiTest
    @DisplayName("Scheduled ContractCall full lifecycle - create, sign, execute fees")
    final Stream<DynamicTest> scheduledContractCallFullLifecycleFees() {
        final var schedulePayer = "contractSchedulePayer";
        return hapiTest(
                uploadInitCode(SIMPLE_UPDATE),
                cryptoCreate(schedulePayer).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                contractCreate(SIMPLE_UPDATE).gas(300_000L),
                scheduleCreate(
                                "contractCallSchedule",
                                contractCall(SIMPLE_UPDATE, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                        .gas(100_000)
                                        .fee(ONE_HBAR))
                        .designatingPayer(schedulePayer)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("createTxn")
                        .fee(ONE_HBAR),
                scheduleSign("contractCallSchedule")
                        .alsoSigningWith(schedulePayer)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER, schedulePayer)
                        .via("signTxn")
                        .fee(ONE_HBAR),
                validateChargedUsd("signTxn", BASE_FEE_SCHEDULE_SIGN + SIGNATURE_FEE_AFTER_MULTIPLIER, 1.0),
                withOpContext((spec, _) -> {
                    var triggeredTx = getTxnRecord("createTxn").scheduled();
                    allRunFor(spec, triggeredTx);
                    assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus());
                }));
    }
}
