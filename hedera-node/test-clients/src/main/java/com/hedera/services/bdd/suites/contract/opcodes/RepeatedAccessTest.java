// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Asserts an address is warm for the rest of the transaction once accessed, as specified by EIP-2929, even
 * when it is a system account whose access the EVM short-circuits; and that a precompile is warm from the
 * start of the transaction.
 */
@Tag(SMART_CONTRACT)
@DisplayName("repeated access")
public class RepeatedAccessTest {
    private static final long COLD_ACCESS_COST = 2600L;
    private static final long WARM_ACCESS_COST = 100L;
    // A genesis system account without code, whose accesses the EVM short-circuits; unlike accounts 0.0.1
    // through 0.0.17, its long-zero address is outside the precompile address range, so it starts cold
    private static final long SYSTEM_ACCOUNT_NUM = 42L;
    // The BLAKE2F precompile, warm from the start of every transaction as every precompile is
    private static final long PRECOMPILE_NUM = 9L;
    // Each RepeatedAccess function with the gas its measurement adds to the account access cost,
    // for the stack setup around the opcode and the closing GAS read
    private static final List<Access> ACCESSES = List.of(
            new Access("callCosts", 25L),
            new Access("callCodeCosts", 25L),
            new Access("delegateCallCosts", 22L),
            new Access("staticCallCosts", 22L),
            new Access("balanceCosts", 11L),
            new Access("extCodeSizeCosts", 11L),
            new Access("extCodeHashCosts", 11L),
            new Access("extCodeCopyCosts", 14L));
    private static final List<String> EVM_VERSIONS =
            List.of("v0.30", "v0.34", "v0.38", "v0.46", "v0.50", "v0.51", "v0.65", "v0.66", "v0.67", "v0.70");

    @Contract(contract = "RepeatedAccess", creationGas = 1_000_000L)
    static SpecContract repeatedAccess;

    @HapiTest
    @DisplayName("the second access to a system account costs the same as access to a warm address")
    final Stream<DynamicTest> secondAccessToSystemAccountIsWarm() {
        return hapiTest(accessesTo(SYSTEM_ACCOUNT_NUM, "", COLD_ACCESS_COST, WARM_ACCESS_COST));
    }

    @HapiTest
    @DisplayName("every access to a precompile costs the same as access to a warm address")
    final Stream<DynamicTest> everyAccessToPrecompileIsWarm() {
        return hapiTest(accessesTo(PRECOMPILE_NUM, "Precompile", WARM_ACCESS_COST, WARM_ACCESS_COST));
    }

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    @DisplayName(
            "the second access to a system account costs the same as access to a warm address on every EVM version")
    final Stream<DynamicTest> secondAccessToSystemAccountIsWarmOnEveryEvmVersion() {
        return hapiTest(Stream.concat(
                        Stream.of(repeatedAccess.getInfo()),
                        EVM_VERSIONS.stream()
                                .flatMap(version -> Stream.concat(
                                        Stream.of(overriding("contracts.evm.version", version)),
                                        Arrays.stream(accessesTo(
                                                SYSTEM_ACCOUNT_NUM, version, COLD_ACCESS_COST, WARM_ACCESS_COST)))))
                .toArray(SpecOperation[]::new));
    }

    /**
     * Returns operations that measure two consecutive accesses to the given account with every
     * {@code RepeatedAccess} function, and assert the measured costs.
     *
     * @param num the number of the account to access
     * @param txnSuffix a suffix making the transaction names unique within a spec
     * @param firstAccessCost the expected cost of the first account access
     * @param secondAccessCost the expected cost of the second account access
     * @return the operations
     */
    private static SpecOperation[] accessesTo(
            final long num, final String txnSuffix, final long firstAccessCost, final long secondAccessCost) {
        return ACCESSES.stream()
                .flatMap(access -> {
                    final var txnName = access.function() + txnSuffix;
                    return Stream.of(
                            doingContextual(spec -> allRunFor(
                                    spec,
                                    repeatedAccess
                                            .call(access.function(), mirrorAddrWith(spec, num))
                                            .gas(100_000L)
                                            .via(txnName))),
                            getTxnRecord(txnName)
                                    .hasPriority(recordWith()
                                            .contractCallResult(resultWith()
                                                    .resultViaFunctionName(
                                                            access.function(),
                                                            repeatedAccess.name(),
                                                            isLiteralResult(new Object[] {
                                                                access.costWith(firstAccessCost),
                                                                access.costWith(secondAccessCost)
                                                            })))));
                })
                .toArray(SpecOperation[]::new);
    }

    private record Access(String function, long overhead) {
        BigInteger costWith(final long accessCost) {
            return BigInteger.valueOf(accessCost + overhead);
        }
    }
}
