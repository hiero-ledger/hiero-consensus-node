// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.file.FileUpdateSuite.CIVILIAN;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(11)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableHip1215Tests {
    private static final long SCHEDULABLE_GAS_LIMIT = 5_000_000L;

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @Contract(contract = "PayableConstructor")
    static SpecContract miscContract;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "contracts.systemContract.scheduleService.scheduleCall.enabled",
                "true",
                "contracts.maxGasPerSecBackend",
                "" + SCHEDULABLE_GAS_LIMIT));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hasCapacityUntilFullyScheduled() {
        final long lifetime = 900;
        final long gasLimit = 2_000_000L;
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                miscContract.getInfo(),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HUNDRED_HBARS),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                sourcingContextual(spec -> {
                    final int numSchedulesBeforeFull = (int) ((SCHEDULABLE_GAS_LIMIT + gasLimit - 1) / gasLimit);
                    final long targetExpiry = startConsensusTime.get().getEpochSecond() + lifetime;
                    return blockingOrder(IntStream.rangeClosed(1, numSchedulesBeforeFull)
                            .mapToObj(i -> blockingOrder(
                                    contract.call(
                                                    "hasScheduleCapacityProxy",
                                                    BigInteger.valueOf(targetExpiry),
                                                    BigInteger.valueOf(gasLimit))
                                            .andAssert(txn -> txn.hasResults(resultWith()
                                                    .resultThruAbi(
                                                            getABIFor(
                                                                    FUNCTION,
                                                                    "hasScheduleCapacityProxy",
                                                                    contract.name()),
                                                            ContractFnResultAsserts.isLiteralResult(
                                                                    new Object[] {i != numSchedulesBeforeFull})))),
                                    i == numSchedulesBeforeFull
                                            ? noOp()
                                            : scheduleCreate(
                                                            "call" + i,
                                                            contractCall(miscContract.name())
                                                                    .memo("memo" + i)
                                                                    .gas(gasLimit))
                                                    .waitForExpiry()
                                                    .expiringAt(targetExpiry)
                                                    .designatingPayer(CIVILIAN)))
                            .toArray(SpecOperation[]::new));
                }));
    }
}
