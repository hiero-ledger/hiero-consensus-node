// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.records.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of LogsSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(SMART_CONTRACT)
public class AtomicLogsSuite {

    private static final long GAS_TO_OFFER = 25_000L;

    private static final String CONTRACT = "Logs";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> log0Works() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                atomicBatch(contractCall(CONTRACT, "log0", BigInteger.valueOf(15))
                                .via("log0")
                                .gas(GAS_TO_OFFER)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("log0")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith().noTopics().longValue(15)))
                                        .gasUsed(22_489))));
    }

    @HapiTest
    final Stream<DynamicTest> log1Works() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                atomicBatch(contractCall(CONTRACT, "log1", BigInteger.valueOf(15))
                                .via("log1")
                                .gas(GAS_TO_OFFER)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("log1")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith()
                                                .noData()
                                                .withTopicsInOrder(List.of(
                                                        eventSignatureOf("Log1(uint256)"),
                                                        parsedToByteString(0, 0, 15)))))
                                        .gasUsed(22_787))));
    }

    @HapiTest
    final Stream<DynamicTest> log2Works() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                atomicBatch(contractCall(CONTRACT, "log2", BigInteger.ONE, BigInteger.TWO)
                                .gas(GAS_TO_OFFER)
                                .via("log2")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("log2")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith()
                                                .noData()
                                                .withTopicsInOrder(List.of(
                                                        eventSignatureOf("Log2(uint256,uint256)"),
                                                        parsedToByteString(0, 0, 1),
                                                        parsedToByteString(0, 0, 2)))))
                                        .gasUsed(23_456))));
    }

    @HapiTest
    final Stream<DynamicTest> log3Works() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                atomicBatch(contractCall(CONTRACT, "log3", BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3))
                                .gas(GAS_TO_OFFER)
                                .via("log3")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("log3")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith()
                                                .noData()
                                                .withTopicsInOrder(List.of(
                                                        eventSignatureOf("Log3(uint256,uint256,uint256)"),
                                                        parsedToByteString(0, 0, 1),
                                                        parsedToByteString(0, 0, 2),
                                                        parsedToByteString(0, 0, 3)))))
                                        .gasUsed(24_122))));
    }

    @HapiTest
    final Stream<DynamicTest> log4Works() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                atomicBatch(contractCall(
                                        CONTRACT,
                                        "log4",
                                        BigInteger.ONE,
                                        BigInteger.TWO,
                                        BigInteger.valueOf(3),
                                        BigInteger.valueOf(4))
                                .gas(GAS_TO_OFFER)
                                .via("log4")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("log4")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith()
                                                .longValue(4)
                                                .withTopicsInOrder(List.of(
                                                        eventSignatureOf("Log4(uint256,uint256,uint256," + "uint256)"),
                                                        parsedToByteString(0, 0, 1),
                                                        parsedToByteString(0, 0, 2),
                                                        parsedToByteString(0, 0, 3)))))
                                        .gasUsed(24_918))));
    }
}
