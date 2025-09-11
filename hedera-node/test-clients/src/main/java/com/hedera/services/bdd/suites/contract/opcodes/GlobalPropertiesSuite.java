// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.*;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import java.math.BigInteger;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class GlobalPropertiesSuite {
    private static final String CONTRACT = "GlobalProperties";
    private static final String GET_CHAIN_ID = "getChainID";
    private static final String GET_BASE_FEE = "getBaseFee";
    private static final String GET_GAS_LIMIT = "getGasLimit";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> chainIdWorks() {
        final var defaultChainId = BigInteger.valueOf(295L);
        final var devChainId = BigInteger.valueOf(298L);
        final Set<Object> acceptableChainIds = Set.of(devChainId, defaultChainId);
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, GET_CHAIN_ID).via("chainId"),
                getTxnRecord("chainId")
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, GET_CHAIN_ID, CONTRACT),
                                                isOneOfLiteral(acceptableChainIds)))),
                contractCallLocal(CONTRACT, GET_CHAIN_ID)
                        .nodePayment(1_234_567)
                        .has(ContractFnResultAsserts.resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, GET_CHAIN_ID, CONTRACT),
                                        isOneOfLiteral(acceptableChainIds))));
    }

    @HapiTest
    final Stream<DynamicTest> baseFeeWorks() {
        final var expectedBaseFee = BigInteger.valueOf(0);
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, GET_BASE_FEE).via("baseFee"),
                getTxnRecord("baseFee")
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, GET_BASE_FEE, CONTRACT),
                                                isLiteralResult(new Object[] {BigInteger.valueOf(0)})))),
                contractCallLocal(CONTRACT, GET_BASE_FEE)
                        .nodePayment(1_234_567)
                        .has(ContractFnResultAsserts.resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, GET_BASE_FEE, CONTRACT),
                                        ContractFnResultAsserts.isLiteralResult(new Object[] {expectedBaseFee}))));
    }

    @SuppressWarnings("java:S5960")
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> coinbaseWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "getCoinbase").via("coinbase"),
                withOpContext((spec, opLog) -> {
                    final var fundingAccount = spec.setup().fundingAccount();
                    final var expectedCoinbase = parsedToByteString(
                            fundingAccount.getShardNum(), fundingAccount.getRealmNum(), fundingAccount.getAccountNum());

                    final var callLocal = contractCallLocal(CONTRACT, "getCoinbase")
                            .nodePayment(1_234_567)
                            .saveResultTo("callLocalCoinbase");
                    final var callRecord = getTxnRecord("coinbase");

                    allRunFor(spec, callRecord, callLocal);

                    final var recordResult = callRecord.getResponseRecord().getContractCallResult();
                    final var callLocalResult = spec.registry().getBytes("callLocalCoinbase");
                    Assertions.assertEquals(recordResult.getContractCallResult(), expectedCoinbase);
                    Assertions.assertArrayEquals(callLocalResult, expectedCoinbase.toByteArray());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> gasLimitWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                doSeveralWithStartupConfig("contracts.maxGasPerSec", value -> {
                    final var gasLimit = Long.parseLong(value);
                    return specOps(
                            contractCall(CONTRACT, GET_GAS_LIMIT)
                                    .via("gasLimit")
                                    .gas(gasLimit),
                            getTxnRecord("gasLimit")
                                    .logged()
                                    .hasPriority(recordWith()
                                            .contractCallResult(resultWith()
                                                    .resultThruAbi(
                                                            getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                            isLiteralResult(
                                                                    new Object[] {BigInteger.valueOf(gasLimit)})))),
                            contractCallLocal(CONTRACT, GET_GAS_LIMIT)
                                    .gas(gasLimit)
                                    .nodePayment(1_234_567)
                                    .has(ContractFnResultAsserts.resultWith()
                                            .resultThruAbi(
                                                    getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                    ContractFnResultAsserts.isLiteralResult(
                                                            new Object[] {BigInteger.valueOf(gasLimit)}))));
                }));
    }
}
