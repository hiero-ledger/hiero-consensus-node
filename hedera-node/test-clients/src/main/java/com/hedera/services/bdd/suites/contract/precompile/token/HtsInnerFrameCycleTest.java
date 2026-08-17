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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

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

    public record FrameCycleTestInput(long childGas, long txGas, String functionName, Object... params) {

        static final int entryCount = 20;
        static final int repetitions =
                60; // more than consensus.handle.maxFollowingRecords (MAX_CHILD_RECORDS_EXCEEDED)
        static final int expectedCycles = 20;
        static final int expectedGasUsedForExecution = 15284;

        public static FrameCycleTestInput transferTokensCycle(long childGas) {
            final int additionalCycleGas = 1000; // gas for regular cycle
            return new FrameCycleTestInput(
                    childGas,
                    (expectedGasUsedForExecution + additionalCycleGas) * (expectedCycles),
                    "transferTokensCycle",
                    BigInteger.valueOf(tokenId.get().getTokenNum()),
                    BigInteger.valueOf(accountId.get().getAccountNum()),
                    BigInteger.valueOf(entryCount),
                    BigInteger.valueOf(repetitions),
                    BigInteger.valueOf(childGas));
        }

        public static FrameCycleTestInput transferTokensCycleWithChildFrame(long childGas) {
            final int additionalOuterCycleGas = 4000; // just enough gas for outer cycle
            return new FrameCycleTestInput(
                    childGas,
                    (expectedGasUsedForExecution + additionalOuterCycleGas) * (expectedCycles),
                    "transferTokensCycleWithChildFrame",
                    BigInteger.valueOf(tokenId.get().getTokenNum()),
                    BigInteger.valueOf(accountId.get().getAccountNum()),
                    BigInteger.valueOf(entryCount),
                    BigInteger.valueOf(repetitions),
                    BigInteger.valueOf(childGas + additionalOuterCycleGas),
                    BigInteger.valueOf(childGas));
        }
    }

    @HapiTest
    public Stream<DynamicTest> innerFrameCycleZeroGasTest() {
        return Stream.of(
                        FrameCycleTestInput.transferTokensCycle(0), // minimum gas
                        FrameCycleTestInput.transferTokensCycle(1), // low gas
                        FrameCycleTestInput.transferTokensCycle(1_000), // some gas, but less than required
                        FrameCycleTestInput.transferTokensCycle(20_000), // enough gas
                        FrameCycleTestInput.transferTokensCycleWithChildFrame(0), // minimum gas
                        FrameCycleTestInput.transferTokensCycleWithChildFrame(1), // low gas
                        FrameCycleTestInput.transferTokensCycleWithChildFrame(1_000) // some gas, but less than required
                        )
                .map(input -> namedHapiTest(
                        "%s gas=%s".formatted(input.functionName(), input.childGas()),
                        doingContextual(e -> transferTokensCycleFrames(e, input))));
    }

    public void transferTokensCycleFrames(HapiSpec spec, FrameCycleTestInput input) {
        final var op = contractCall(CONTRACT, input.functionName(), input.params())
                .via(ATTACK_TXN)
                .hasKnownStatusFrom(MAX_CHILD_RECORDS_EXCEEDED, INSUFFICIENT_GAS, SUCCESS)
                .noLogging()
                .gas(input.txGas());
        allRunFor(
                spec,
                op,
                getTxnRecord(ATTACK_TXN)
                        .exposingAllTo(e -> Assertions.assertEquals(FrameCycleTestInput.expectedCycles, e.size()))
                        .andAllChildRecords());
    }
}
