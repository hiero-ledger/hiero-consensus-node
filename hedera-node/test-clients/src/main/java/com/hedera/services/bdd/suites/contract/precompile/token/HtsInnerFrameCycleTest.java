// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.OPS_DURATION_THROTTLE_CAPACITY;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.OPS_DURATION_THROTTLE_UNITS_FREED_PER_SECOND;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.THROTTLE_THROTTLE_BY_OPS_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class HtsInnerFrameCycleTest {

    private static final String CONTRACT = "HtsInnerFrameCycle";
    private static final String ATTACK_TXN = "HtsInnerFrameCycleTx";

    @Contract(contract = CONTRACT, creationGas = 2_000_000L)
    static SpecContract contract;

    @FungibleToken(name = "fungibleToken")
    static SpecFungibleToken ft;

    public static AtomicReference<TokenID> tokenId = new AtomicReference<>();

    @Account(tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount account;

    static final AtomicReference<AccountID> accountId = new AtomicReference<>();

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                THROTTLE_THROTTLE_BY_OPS_DURATION, Boolean.toString(true),
                OPS_DURATION_THROTTLE_CAPACITY, Long.toString(100_000_000),
                OPS_DURATION_THROTTLE_UNITS_FREED_PER_SECOND, Long.toString(100_000_000)));
        lifecycle.doAdhoc(
                contract.getInfo(),
                ft.getInfo().andGet(e -> tokenId.set(e.getTokenId())),
                account.getInfo().andAssert(e -> e.exposingIdTo(accountId::set)));
    }

    @HapiTest
    public Stream<DynamicTest> innerFrameCycleZeroGasTest() {
        return Stream.of(
                        0, // minimum gas
                        1, // low gas
                        10000, // some gas, but less than required
                        20000 // enough gas
                        )
                .map(gas -> namedHapiTest(
                        "innerFrameCycl gas=" + gas, doingContextual(e -> transferTokensCycleFrames(e, gas))));
    }

    public void transferTokensCycleFrames(HapiSpec spec, final int childGas) {
        final int entryCount = 20;
        final int repetitions = 60;
        final int expectedGasUsedForCycle = 15284;
        // amount of transfer repetitions up to parent frame gas
        final int expectedCycles = 20;
        final var op = contractCall(
                        CONTRACT,
                        "transferTokensCycle",
                        BigInteger.valueOf(tokenId.get().getTokenNum()),
                        BigInteger.valueOf(accountId.get().getAccountNum()),
                        BigInteger.valueOf(entryCount),
                        BigInteger.valueOf(repetitions),
                        BigInteger.valueOf(childGas))
                .via(ATTACK_TXN)
                .hasKnownStatusFrom(MAX_CHILD_RECORDS_EXCEEDED, INSUFFICIENT_GAS)
                .noLogging()
                .gas(expectedGasUsedForCycle * (expectedCycles + 1));
        allRunFor(
                spec,
                op,
                getTxnRecord(ATTACK_TXN)
                        .exposingAllTo(e -> Assertions.assertEquals(expectedCycles, e.size()))
                        .andAllChildRecords());
    }
}
