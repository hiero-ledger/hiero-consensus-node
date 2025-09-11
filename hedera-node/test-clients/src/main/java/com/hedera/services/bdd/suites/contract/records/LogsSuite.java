// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.records;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;

import com.hedera.services.bdd.junit.HapiTest;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class LogsSuite {

    private static final long GAS_TO_OFFER = 25_000L;

    private static final String CONTRACT = "Logs";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> log0Works() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "log0", BigInteger.valueOf(15))
                        .via("log0")
                        .gas(GAS_TO_OFFER),
                getTxnRecord("log0")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith().noTopics().longValue(15)))
                                        .gasUsed(22_489))));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> log1Works() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "log1", BigInteger.valueOf(15))
                        .via("log1")
                        .gas(GAS_TO_OFFER),
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
                contractCall(CONTRACT, "log2", BigInteger.ONE, BigInteger.TWO)
                        .gas(GAS_TO_OFFER)
                        .via("log2"),
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
                contractCall(CONTRACT, "log3", BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3))
                        .gas(GAS_TO_OFFER)
                        .via("log3"),
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
                contractCall(
                                CONTRACT,
                                "log4",
                                BigInteger.ONE,
                                BigInteger.TWO,
                                BigInteger.valueOf(3),
                                BigInteger.valueOf(4))
                        .gas(GAS_TO_OFFER)
                        .via("log4"),
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
