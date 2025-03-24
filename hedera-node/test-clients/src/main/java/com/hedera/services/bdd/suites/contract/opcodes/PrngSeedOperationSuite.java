// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class PrngSeedOperationSuite {
    private static final long GAS_TO_OFFER = 400_000L;
    private static final String THE_PRNG_CONTRACT = "PrngSeedOperationContract";
    private static final String BOB = "bob";

    private static final String GET_SEED = "getPseudorandomSeed";

    @HapiTest
    final Stream<DynamicTest> multipleCallsHaveIndependentResults() {
        final var prng = THE_PRNG_CONTRACT;
        final var gasToOffer = 400_000;
        final var numCalls = 5;
        final List<String> prngSeeds = new ArrayList<>();
        return hapiTest(
                uploadInitCode(prng),
                contractCreate(prng),
                withOpContext((spec, opLog) -> {
                    for (int i = 0; i < numCalls; i++) {
                        final var txn = "call" + i;
                        final var call =
                                contractCall(prng, GET_SEED).gas(gasToOffer).via(txn);
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
    final Stream<DynamicTest> prngPrecompileHappyPathWorks() {
        final var prng = THE_PRNG_CONTRACT;
        final var randomBits = "randomBits";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(prng),
                contractCreate(prng),
                contractCall(prng, GET_SEED)
                        .gas(GAS_TO_OFFER)
                        .payingWith(BOB)
                        .via(randomBits)
                        .logged(),
                getTxnRecord(randomBits)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultViaFunctionName(
                                                GET_SEED, prng, isRandomResult((new Object[] {new byte[32]}))))));
    }
}
