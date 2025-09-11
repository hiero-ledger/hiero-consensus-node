// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.contract.Utils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
/** - CONCURRENCY STATUS - . Can run concurrent without temporarySStoreRefundTest() */
public class SStoreSuite {

    private static final Logger log = LogManager.getLogger(SStoreSuite.class);
    public static final int MAX_CONTRACT_STORAGE_KB = 1024;
    public static final int MAX_CONTRACT_GAS = 15_000_000;
    private static final String GET_CHILD_VALUE = "getChildValue";

    // This test is failing with CONSENSUS_GAS_EXHAUSTED prior the refactor.
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> multipleSStoreOpsSucceed() {
        final var contract = "GrowArray";
        final var GAS_TO_OFFER = 6_000_000L;
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                withOpContext((spec, opLog) -> {
                    final var step = 16;
                    final List<SpecOperation> subOps = new ArrayList<>();

                    for (int sizeNow = step; sizeNow < MAX_CONTRACT_STORAGE_KB; sizeNow += step) {
                        final var subOp1 = contractCall(contract, "growTo", BigInteger.valueOf(sizeNow))
                                .gas(GAS_TO_OFFER)
                                .logged();
                        subOps.add(subOp1);
                    }
                    CustomSpecAssert.allRunFor(spec, subOps);
                }),
                withOpContext((spec, opLog) -> {
                    final var numberOfIterations = 10;
                    final List<SpecOperation> subOps = new ArrayList<>();

                    for (int i = 0; i < numberOfIterations; i++) {
                        final var subOp1 = contractCall(
                                        contract,
                                        "changeArray",
                                        BigInteger.valueOf(
                                                ThreadLocalRandom.current().nextInt(1000)))
                                .logged();
                        subOps.add(subOp1);
                    }
                    CustomSpecAssert.allRunFor(spec, subOps);
                }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> childStorage() {
        // Successfully exceeds deprecated max contract storage of 1 KB
        final var contract = "ChildStorage";
        return hapiTest(flattened(
                uploadInitCode(contract),
                contractCreate(contract),
                withOpContext((spec, opLog) -> {
                    final var almostFullKb = MAX_CONTRACT_STORAGE_KB * 3 / 4;
                    final var kbPerStep = 16;

                    for (int childKbStorage = 0; childKbStorage <= almostFullKb; childKbStorage += kbPerStep) {
                        final var subOp1 = contractCall(
                                        contract,
                                        "growChild",
                                        BigInteger.valueOf(0),
                                        BigInteger.valueOf(kbPerStep),
                                        BigInteger.valueOf(17))
                                .gas(MAX_CONTRACT_GAS)
                                .via("small" + childKbStorage);
                        final var subOp2 = contractCall(
                                        contract,
                                        "growChild",
                                        BigInteger.valueOf(1),
                                        BigInteger.valueOf(kbPerStep),
                                        BigInteger.valueOf(19))
                                .gas(MAX_CONTRACT_GAS)
                                .via("large" + childKbStorage);
                        final var subOp3 =
                                getTxnRecord("small" + childKbStorage).logged();
                        final var subOp4 =
                                getTxnRecord("large" + childKbStorage).logged();
                        CustomSpecAssert.allRunFor(spec, subOp1, subOp2, subOp3, subOp4);
                    }
                }),
                valuesMatch(contract, 19, 17, 19),
                contractCall(contract, "setZeroReadOne", BigInteger.valueOf(23)),
                valuesMatch(contract, 23, 23, 19),
                contractCall(contract, "setBoth", BigInteger.valueOf(29)),
                valuesMatch(contract, 29, 29, 29)));
    }

    private HapiSpecOperation[] valuesMatch(
            final String contract, final long parent, final long child0, final long child1) {
        return new HapiSpecOperation[] {
            contractCallLocal(contract, GET_CHILD_VALUE, BigInteger.ZERO)
                    .has(resultWith()
                            .resultThruAbi(
                                    getABIFor(FUNCTION, GET_CHILD_VALUE, contract),
                                    isLiteralResult(new Object[] {BigInteger.valueOf(child0)}))),
            contractCallLocal(contract, GET_CHILD_VALUE, BigInteger.ONE)
                    .has(resultWith()
                            .resultThruAbi(
                                    getABIFor(FUNCTION, GET_CHILD_VALUE, contract),
                                    isLiteralResult(new Object[] {BigInteger.valueOf(child1)}))),
            contractCallLocal(contract, "getMyValue")
                    .has(resultWith()
                            .resultThruAbi(
                                    getABIFor(FUNCTION, "getMyValue", contract),
                                    isLiteralResult(new Object[] {BigInteger.valueOf(parent)}))),
        };
    }

    @SuppressWarnings("java:S5669")
    @HapiTest
    final Stream<DynamicTest> benchmarkSingleSetter() {
        final var contract = "Benchmark";
        final var GAS_LIMIT = 1_000_000;
        var value = Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000005")
                .toArray();
        return hapiTest(
                cryptoCreate("payer").balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract).payingWith("payer").via("creationTx").gas(GAS_LIMIT),
                contractCall(contract, "twoSSTOREs", value).gas(GAS_LIMIT).via("storageTx"),
                getTxnRecord("storageTx").logged(),
                contractCallLocal(contract, "counter")
                        .nodePayment(1_234_567)
                        .has(ContractFnResultAsserts.resultWith()
                                .resultThruAbi(
                                        Utils.getABIFor(FUNCTION, "counter", contract),
                                        ContractFnResultAsserts.isLiteralResult(
                                                new Object[] {BigInteger.valueOf(1L)}))));
    }
}
