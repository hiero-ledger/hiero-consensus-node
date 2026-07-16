// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.validation;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class ChildCallDataSizeValidationTest {

    private static final int TRANSACTION_MAX_BYTES = 6144;
    private static final int TRANSACTION_MAX_GAS = 15_000_000;

    @Contract(contract = "ChildCallDataSizeValidationContract", creationGas = 2_000_000L)
    static SpecContract contract;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "hedera.transaction.maxBytes",
                String.valueOf(TRANSACTION_MAX_BYTES),
                "contracts.systemContract.scheduleService.signSchedule.from.contract.enabled",
                "true"));
    }

    @NonNull
    private Stream<DynamicTest> functionCallDataSizeValidationTest(
            final String functionName,
            int inputArrayParamSize1,
            int inputArrayParamSize2,
            boolean callResult,
            final ResponseCodeEnum callResponseCode) {
        final var txName = functionName + "Tx-";
        return hapiTest(
                contract.call(functionName, asHeadlongAddress(new byte[20]), BigInteger.valueOf(inputArrayParamSize1))
                        .gas(TRANSACTION_MAX_GAS)
                        .via(txName + "success")
                        .exposingResultTo(res -> Assertions.assertEquals(callResult, res[0])),
                getTxnRecord(txName + "success")
                        .andAllChildRecords()
                        .hasChildRecordCount(1)
                        .hasChildRecords(recordWith().status(callResponseCode)),
                // Halt
                contract.call(functionName, asHeadlongAddress(new byte[20]), BigInteger.valueOf(inputArrayParamSize2))
                        .gas(TRANSACTION_MAX_GAS)
                        .via(txName + "halt")
                        .exposingResultTo(res -> Assertions.assertFalse((Boolean) res[0])),
                getTxnRecord(txName + "halt")
                        .andAllChildRecords()
                        .hasChildRecordCount(0) // there should be no child records, because it is halts
                );
    }

    @LeakyHapiTest
    public Stream<DynamicTest> htsFunctionCallDataSizeValidationTest() {
        return functionCallDataSizeValidationTest(
                "callAssociateTokens",
                10, // allowed tokenCount
                TRANSACTION_MAX_BYTES / 32 + 1, // oversized tokenCount
                true,
                INVALID_ACCOUNT_ID);
    }

    @LeakyHapiTest
    public Stream<DynamicTest> hasFunctionCallDataSizeValidationTest() {
        return functionCallDataSizeValidationTest(
                "callIsAuthorized",
                100, // allowed simMapSize
                TRANSACTION_MAX_BYTES + 1, // oversized simMapSize
                true,
                SUCCESS);
    }

    @LeakyHapiTest
    public Stream<DynamicTest> hssFunctionCallDataSizeValidationTest() {
        return functionCallDataSizeValidationTest(
                "callSignSchedule",
                100, // allowed simMapSize
                TRANSACTION_MAX_BYTES + 1, // oversized simMapSize
                false,
                INVALID_SCHEDULE_ID);
    }
}
