// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isRandomResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class PrngPrecompileSuite {
    private static final long GAS_TO_OFFER = 400_000L;
    private static final String THE_GRACEFULLY_FAILING_PRNG_CONTRACT = "GracefullyFailingPrng";
    private static final String THE_PRNG_CONTRACT = "PrngSystemContract";
    private static final String BOB = "bob";

    private static final String GET_SEED = "getPseudorandomSeed";
    private static final String GET_SEED_PAYABLE = "getPseudorandomSeedPayable";
    private static final String EXPLICIT_LARGE_PARAMS =
            "d83bf9a10000000000000000000000d83bf9a10000d83bf9a1000d83bf9a10000000d83bf9a108000d83bf9a100000d83bf9a1000000"
                    + "0000d83bf9a100000d83bf9a1000000d83bf9a100339000000d83bf9a1000000000123456789012345678901234"
                    + "5678901234567890000000000000000000000000123456789012345678901234567890123456789000000000"
                    + "000d83bf9a1083bf9a1000d83bf9a10000000d83bf9a10000000000000000026e790000000000000000000000000000"
                    + "00000000d83bf9a1000000000d83bf9a1000";

    @HapiTest
    final Stream<DynamicTest> multipleCallsHaveIndependentResults() {
        final var prng = THE_PRNG_CONTRACT;
        final var gasToOffer = 400_000;
        final var numCalls = 5;
        final List<String> prngSeeds = new ArrayList<>();
        return hapiTest(
                uploadInitCode(prng), contractCreate(prng),
                withOpContext((spec, opLog) -> {
                            for (int i = 0; i < numCalls; i++) {
                                final var txn = "call" + i;
                                final var call = contractCall(prng, GET_SEED)
                                        .gas(gasToOffer)
                                        .via(txn);
                                final var lookup = getTxnRecord(txn).andAllChildRecords();
                                allRunFor(spec, call, lookup);
                                final var response = lookup.getResponseRecord();
                                final var rawResult = response.getContractCallResult()
                                        .getContractCallResult()
                                        .toByteArray();
                                // Since this contract returns the result of the Prng system
                                // contract, its call result
                                // should be identical to the result of the system contract
                                // in the child record
                                for (final var child : lookup.getChildRecords()) {
                                    if (child.hasContractCallResult()) {
                                        assertArrayEquals(
                                                rawResult,
                                                child.getContractCallResult()
                                                        .getContractCallResult()
                                                        .toByteArray());
                                    }
                                }
                                prngSeeds.add(CommonUtils.hex(rawResult));
                            }
                            opLog.info("Got prng seeds  : {}", prngSeeds);
                            assertEquals(
                                    prngSeeds.size(),
                                    new HashSet<>(prngSeeds).size(),
                                    "An N-3 running hash was repeated, which is" + " inconceivable");
                        }),
                        // It's possible to call these contracts in a static context with no issues
                        contractCallLocal(prng, GET_SEED).gas(gasToOffer));
    }

    @HapiTest
    final Stream<DynamicTest> emptyInputCallFails() {
        final var prng = THE_PRNG_CONTRACT;
        final var emptyInputCall = "emptyInputCall";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(prng),
                contractCreate(prng),
                sourcing(() -> contractCall(prng, GET_SEED)
                        .withExplicitParams(
                                () -> CommonUtils.hex(Bytes.fromBase64String("").toArray()))
                        .gas(GAS_TO_OFFER)
                        .payingWith(BOB)
                        .via(emptyInputCall)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTxnRecord(emptyInputCall).andAllChildRecords().logged().saveTxnRecordToRegistry(emptyInputCall),
                withOpContext((spec, ignore) -> {
                    final var gasUsed = spec.registry()
                            .getTransactionRecord(emptyInputCall)
                            .getContractCallResult()
                            .getGasUsed();
                    assertEquals(320000, gasUsed);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> invalidLargeInputFails() {
        final var prng = THE_PRNG_CONTRACT;
        final var largeInputCall = "largeInputCall";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(prng),
                contractCreate(prng),
                sourcing(() -> contractCall(prng, GET_SEED)
                        .withExplicitParams(() -> CommonUtils.hex(
                                Bytes.fromBase64String(EXPLICIT_LARGE_PARAMS).toArray()))
                        .gas(GAS_TO_OFFER)
                        .payingWith(BOB)
                        .via(largeInputCall)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTxnRecord(largeInputCall).andAllChildRecords().logged().saveTxnRecordToRegistry(largeInputCall),
                withOpContext((spec, ignore) -> {
                    final var gasUsed = spec.registry()
                            .getTransactionRecord(largeInputCall)
                            .getContractCallResult()
                            .getGasUsed();
                    assertEquals(320000, gasUsed);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> nonSupportedAbiCallGracefullyFails() {
        final var prng = THE_GRACEFULLY_FAILING_PRNG_CONTRACT;
        final var failedCall = "failedCall";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(prng),
                contractCreate(prng),
                sourcing(() -> contractCall(prng, "performNonExistingServiceFunctionCall")
                        .gas(GAS_TO_OFFER)
                        .payingWith(BOB)
                        .via(failedCall)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTxnRecord(failedCall).andAllChildRecords().logged().saveTxnRecordToRegistry(failedCall),
                withOpContext((spec, ignore) -> {
                    final var gasUsed = spec.registry()
                            .getTransactionRecord(failedCall)
                            .getContractCallResult()
                            .getGasUsed();
                    assertEquals(394210, gasUsed);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> functionCallWithLessThanFourBytesFailsGracefully() {
        final var lessThan4Bytes = "lessThan4Bytes";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(THE_PRNG_CONTRACT),
                contractCreate(THE_PRNG_CONTRACT),
                sourcing(() -> contractCall(THE_PRNG_CONTRACT, GET_SEED)
                        .withExplicitParams(() -> CommonUtils.hex(
                                Bytes.of((byte) 0xab, (byte) 0xab, (byte) 0xab).toArray()))
                        .gas(GAS_TO_OFFER)
                        .payingWith(BOB)
                        .via(lessThan4Bytes)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                        .logged()),
                getTxnRecord(lessThan4Bytes).andAllChildRecords().logged().saveTxnRecordToRegistry(lessThan4Bytes),
                withOpContext((spec, ignore) -> {
                    final var gasUsed = spec.registry()
                            .getTransactionRecord(lessThan4Bytes)
                            .getContractCallResult()
                            .getGasUsed();
                    assertEquals(320000, gasUsed);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> prngPrecompileHappyPathWorks() {
        final var prng = THE_PRNG_CONTRACT;
        final var randomBits = "randomBits";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(prng),
                contractCreate(prng),
                sourcing(() -> contractCall(prng, GET_SEED)
                        .gas(GAS_TO_OFFER)
                        .payingWith(BOB)
                        .via(randomBits)),
                getTxnRecord(randomBits)
                        .andAllChildRecords()
                        .hasChildRecordCount(1)
                        .hasChildRecords(recordWith()
                                .pseudoRandomBytes()
                                .contractCallResult(resultWith()
                                        .resultViaFunctionName(
                                                GET_SEED, prng, isRandomResult(new Object[] {new byte[32]})))));
    }

    @HapiTest
    final Stream<DynamicTest> prngPrecompileInvalidFeeSubmitted() {
        final var TX = "TX";
        return hapiTest(
                uploadInitCode(THE_PRNG_CONTRACT),
                contractCreate(THE_PRNG_CONTRACT).balance(ONE_HBAR),
                contractCall(THE_PRNG_CONTRACT, GET_SEED_PAYABLE)
                        .gas(GAS_TO_OFFER)
                        .via(TX)
                        .hasKnownStatus(INVALID_CONTRACT_ID),
                getTxnRecord(TX));
    }

    @HapiTest
    final Stream<DynamicTest> prngPrecompileInsufficientGas() {
        final var prng = THE_PRNG_CONTRACT;
        final var randomBits = "randomBits";
        return hapiTest(cryptoCreate(BOB), uploadInitCode(prng), contractCreate(prng), sourcing(() -> contractCall(
                        prng, GET_SEED)
                .gas(1L)
                .payingWith(BOB)
                .via(randomBits)
                .hasPrecheckFrom(OK, INSUFFICIENT_GAS)
                .hasKnownStatus(INSUFFICIENT_GAS)
                .logged()));
    }
}
