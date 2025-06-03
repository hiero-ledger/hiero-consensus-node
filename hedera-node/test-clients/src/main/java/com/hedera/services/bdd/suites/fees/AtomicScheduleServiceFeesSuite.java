// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.OTHER_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_UPDATE;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of ScheduleServiceFeesSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@HapiTestLifecycle
public class AtomicScheduleServiceFeesSuite {

    private static final double BASE_FEE_SCHEDULE_CREATE = 0.01;
    private static final double BASE_FEE_SCHEDULE_SIGN = 0.001;
    private static final double BASE_FEE_SCHEDULE_DELETE = 0.001;
    private static final double BASE_FEE_CONTRACT_CALL = 0.1;
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "scheduling.whitelist",
                "ContractCall,CryptoCreate,CryptoTransfer,FileDelete,FileUpdate,SystemDelete",
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50"));
    }

    private HapiSpecOperation[] scheduleSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR),
            uploadInitCode(SIMPLE_UPDATE),
            cryptoCreate(OTHER_PAYER),
            cryptoCreate(PAYING_SENDER),
            cryptoCreate(RECEIVER).receiverSigRequired(true),
            contractCreate(SIMPLE_UPDATE).gas(300_000L),
        };
    }

    @LeakyRepeatableHapiTest(value = NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, fees = "scheduled-contract-fees.json")
    @DisplayName("Schedule ops have expected USD fees")
    final Stream<DynamicTest> scheduleOpsBaseUSDFees() {
        final String SCHEDULE_NAME = "canonical";
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                flattened(
                        scheduleSetup(),
                        scheduleCreate(
                                        SCHEDULE_NAME,
                                        cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                                .memo("")
                                                .fee(ONE_HBAR))
                                .payingWith(OTHER_PAYER)
                                .via("canonicalCreation")
                                .alsoSigningWith(PAYING_SENDER)
                                .adminKey(OTHER_PAYER),
                        atomicBatch(scheduleCreate(
                                                "tbd",
                                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                                        .memo("")
                                                        .fee(ONE_HBAR))
                                        .payingWith(PAYING_SENDER)
                                        .adminKey(PAYING_SENDER)
                                        .batchKey(BATCH_OPERATOR))
                                .via(ATOMIC_BATCH)
                                .signedByPayerAnd(BATCH_OPERATOR)
                                .payingWith(BATCH_OPERATOR),
                        atomicBatch(
                                        scheduleSign(SCHEDULE_NAME)
                                                .via("canonicalSigning")
                                                .payingWith(PAYING_SENDER)
                                                .alsoSigningWith(RECEIVER)
                                                .batchKey(BATCH_OPERATOR),
                                        scheduleDelete("tbd")
                                                .via("canonicalDeletion")
                                                .payingWith(PAYING_SENDER)
                                                .batchKey(BATCH_OPERATOR),
                                        scheduleCreate(
                                                        "contractCall",
                                                        contractCall(
                                                                        SIMPLE_UPDATE,
                                                                        "set",
                                                                        BigInteger.valueOf(5),
                                                                        BigInteger.valueOf(42))
                                                                .gas(24_000)
                                                                .memo("")
                                                                .fee(ONE_HBAR))
                                                .payingWith(OTHER_PAYER)
                                                .via("canonicalContractCall")
                                                .adminKey(OTHER_PAYER)
                                                .batchKey(BATCH_OPERATOR))
                                .via(ATOMIC_BATCH)
                                .signedByPayerAnd(BATCH_OPERATOR)
                                .payingWith(BATCH_OPERATOR),
                        handleAnyRepeatableQueryPayment(),
                        validateInnerTxnChargedUsd("canonicalCreation", ATOMIC_BATCH, BASE_FEE_SCHEDULE_CREATE, 5.0),
                        validateInnerTxnChargedUsd("canonicalSigning", ATOMIC_BATCH, BASE_FEE_SCHEDULE_SIGN, 5.0),
                        validateInnerTxnChargedUsd("canonicalDeletion", ATOMIC_BATCH, BASE_FEE_SCHEDULE_DELETE, 5.0),
                        validateInnerTxnChargedUsd(
                                "canonicalContractCall", ATOMIC_BATCH, BASE_FEE_CONTRACT_CALL, 5.0)));
    }
}
