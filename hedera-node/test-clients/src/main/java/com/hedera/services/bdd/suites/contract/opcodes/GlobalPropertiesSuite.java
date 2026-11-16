// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.*;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
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
    private static final String GET_GAS_LIMIT_NESTED = "getGasLimitNested";
    private static final String BLOCK_GAS_LIMIT_PROPERTY = "contracts.blockGasLimit";
    // Deliberately different from (and well below) any sane block gas limit, so the assertions
    // below can tell the block gas limit apart from the transaction's own gas limit
    private static final long TXN_GAS = 1_000_000L;

    @HapiTest
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

    /**
     * GASLIMIT is block-scoped (Yellow Paper H.2, {@code μ's[0] ≡ I_H_l}), so {@code block.gaslimit} must be the
     * network's {@code contracts.blockGasLimit} regardless of how much gas the transaction itself supplies.
     */
    @HapiTest
    final Stream<DynamicTest> gasLimitWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                doSeveralWithStartupConfig(BLOCK_GAS_LIMIT_PROPERTY, value -> {
                    final var blockGasLimit = new BigInteger(value);
                    return specOps(
                            contractCall(CONTRACT, GET_GAS_LIMIT)
                                    .via("gasLimit")
                                    .gas(TXN_GAS),
                            getTxnRecord("gasLimit")
                                    .logged()
                                    .hasPriority(recordWith()
                                            .contractCallResult(resultWith()
                                                    .resultThruAbi(
                                                            getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                            isLiteralResult(new Object[] {blockGasLimit})))),
                            contractCallLocal(CONTRACT, GET_GAS_LIMIT)
                                    .gas(TXN_GAS)
                                    .nodePayment(1_234_567)
                                    .has(ContractFnResultAsserts.resultWith()
                                            .resultThruAbi(
                                                    getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                    ContractFnResultAsserts.isLiteralResult(
                                                            new Object[] {blockGasLimit}))));
                }));
    }

    /**
     * A child frame that receives only a 100k gas stipend must still see the block gas limit, not the stipend
     * and not the transaction gas limit.
     */
    @HapiTest
    final Stream<DynamicTest> gasLimitIsBlockScopedInNestedCalls() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                doSeveralWithStartupConfig(BLOCK_GAS_LIMIT_PROPERTY, value -> {
                    final var blockGasLimit = new BigInteger(value);
                    return specOps(
                            contractCall(CONTRACT, GET_GAS_LIMIT_NESTED)
                                    .via("nestedGasLimit")
                                    .gas(TXN_GAS),
                            getTxnRecord("nestedGasLimit")
                                    .logged()
                                    .hasPriority(recordWith()
                                            .contractCallResult(resultWith()
                                                    .resultThruAbi(
                                                            getABIFor(FUNCTION, GET_GAS_LIMIT_NESTED, CONTRACT),
                                                            isLiteralResult(new Object[] {blockGasLimit})))),
                            contractCallLocal(CONTRACT, GET_GAS_LIMIT_NESTED)
                                    .gas(TXN_GAS)
                                    .nodePayment(1_234_567)
                                    .has(ContractFnResultAsserts.resultWith()
                                            .resultThruAbi(
                                                    getABIFor(FUNCTION, GET_GAS_LIMIT_NESTED, CONTRACT),
                                                    ContractFnResultAsserts.isLiteralResult(
                                                            new Object[] {blockGasLimit}))));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> gasLimitWorksForEthereumTransactions() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                doSeveralWithStartupConfig(BLOCK_GAS_LIMIT_PROPERTY, value -> {
                    final var blockGasLimit = new BigInteger(value);
                    return specOps(
                            ethereumCall(CONTRACT, GET_GAS_LIMIT)
                                    .gasLimit(TXN_GAS)
                                    .via("ethGasLimit")
                                    .hasKnownStatus(SUCCESS),
                            getTxnRecord("ethGasLimit")
                                    .logged()
                                    .hasPriority(recordWith()
                                            .contractCallResult(resultWith()
                                                    .resultThruAbi(
                                                            getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                            isLiteralResult(new Object[] {blockGasLimit})))));
                }));
    }

    @LeakyHapiTest(overrides = {BLOCK_GAS_LIMIT_PROPERTY})
    final Stream<DynamicTest> gasLimitTracksBlockGasLimitProperty() {
        final var overriddenBlockGasLimit = BigInteger.valueOf(77_000_000L);
        return hapiTest(
                overriding(BLOCK_GAS_LIMIT_PROPERTY, overriddenBlockGasLimit.toString()),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, GET_GAS_LIMIT).via("gasLimit").gas(TXN_GAS),
                getTxnRecord("gasLimit")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                                isLiteralResult(new Object[] {overriddenBlockGasLimit})))),
                contractCallLocal(CONTRACT, GET_GAS_LIMIT)
                        .gas(TXN_GAS)
                        .nodePayment(1_234_567)
                        .has(ContractFnResultAsserts.resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, GET_GAS_LIMIT, CONTRACT),
                                        ContractFnResultAsserts.isLiteralResult(
                                                new Object[] {overriddenBlockGasLimit}))));
    }
}
