// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Asserts the coinbase address is warm at the start of execution, as specified by EIP-3651.
 */
@Tag(SMART_CONTRACT)
@DisplayName("coinbase access")
public class CoinbaseAccessTest {
    private static final long WARM_ACCESS_COST = 100L;
    // Each CoinbaseAccess function with the gas its measurement adds to the account access cost,
    // for the stack setup around the opcode and the closing GAS read
    private static final List<Access> ACCESSES = List.of(
            new Access("callCosts", 25L),
            new Access("balanceCosts", 11L),
            new Access("extCodeSizeCosts", 11L),
            new Access("extCodeHashCosts", 11L),
            new Access("extCodeCopyCosts", 14L));
    private static final List<String> EVM_VERSIONS =
            List.of("v0.30", "v0.34", "v0.38", "v0.46", "v0.50", "v0.51", "v0.65", "v0.66", "v0.67", "v0.70");

    @Contract(contract = "CoinbaseAccess")
    static SpecContract coinbaseAccess;

    @HapiTest
    @DisplayName("access to the coinbase costs the same as access to a warm address")
    final Stream<DynamicTest> coinbaseAccessIsWarm() {
        return hapiTest(ACCESSES.stream()
                .flatMap(access -> Stream.of(
                        coinbaseAccess.call(access.function()).gas(100_000L).via(access.function()),
                        assertCoinbaseCostIsWarm(access.function(), access)))
                .toArray(SpecOperation[]::new));
    }

    @HapiTest
    @DisplayName("access to the coinbase costs the same as access to a warm address in a local call")
    final Stream<DynamicTest> coinbaseAccessIsWarmInLocalCall() {
        return hapiTest(ACCESSES.stream()
                .map(access -> coinbaseAccess
                        .staticCall(access.function())
                        .andAssert(query -> query.has(resultWith()
                                .resultViaFunctionName(
                                        access.function(),
                                        coinbaseAccess.name(),
                                        isLiteralResult(new Object[] {access.warmCost(), access.warmCost()})))))
                .toArray(SpecOperation[]::new));
    }

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    @DisplayName("access to the coinbase costs the same as access to a warm address on every EVM version")
    final Stream<DynamicTest> coinbaseAccessIsWarmOnEveryEvmVersion() {
        return hapiTest(Stream.concat(
                        Stream.of(coinbaseAccess.getInfo()),
                        EVM_VERSIONS.stream()
                                .flatMap(version -> Stream.concat(
                                        Stream.of(overriding("contracts.evm.version", version)),
                                        ACCESSES.stream().flatMap(access -> {
                                            final var txnName = access.function() + version;
                                            return Stream.of(
                                                    coinbaseAccess
                                                            .call(access.function())
                                                            .gas(100_000L)
                                                            .via(txnName),
                                                    assertCoinbaseCostIsWarm(txnName, access));
                                        }))))
                .toArray(SpecOperation[]::new));
    }

    private static SpecOperation assertCoinbaseCostIsWarm(final String txnName, final Access access) {
        return getTxnRecord(txnName)
                .hasPriority(recordWith()
                        .contractCallResult(resultWith()
                                .resultViaFunctionName(
                                        access.function(),
                                        coinbaseAccess.name(),
                                        isLiteralResult(new Object[] {access.warmCost(), access.warmCost()}))));
    }

    private record Access(String function, long overhead) {
        BigInteger warmCost() {
            return BigInteger.valueOf(WARM_ACCESS_COST + overhead);
        }
    }
}
