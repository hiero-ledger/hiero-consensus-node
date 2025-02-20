// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.sleepToExactly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThree;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.scheduledExecutionResult;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withRecordSpec;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Staking tests that need virtual time for fast execution.
 */
@HapiTestLifecycle
public class RepeatableStakingTests {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                overridingThree(
                        "staking.startThreshold", "" + 10 * ONE_HBAR,
                        "staking.perHbarRewardRate", "1",
                        "staking.rewardBalanceThreshold", "0"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)));
    }

    /**
     * Validates that staking metadata stays up-to-date even when returning to a staked account
     * after a long period of inactivity.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    Stream<DynamicTest> noStakingInteractionsForExtendedPeriodIsFine() {
        final var numPeriodsToElapse = 366;
        return hapiTest(
                cryptoCreate("forgottenStaker").stakedNodeId(0).balance(ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    for (int i = 0; i < numPeriodsToElapse; i++) {
                        allRunFor(
                                spec,
                                waitUntilStartOfNextStakingPeriod(1),
                                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)));
                    }
                }),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "forgottenStaker", 1L)).via("collection"),
                getTxnRecord("collection").hasPaidStakingRewards(List.of(Pair.of("forgottenStaker", 365L))));
    }

    /**
     * Validates that staking metadata stays up-to-date even when returning to a staked account
     * after a long period of inactivity.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    Stream<DynamicTest> scheduledTransactionCrossingThresholdTriggersExpectedRewards() {
        final AtomicReference<Instant> secondBoundary = new AtomicReference<>();
        return hapiTest(
                blockStreamMustIncludePassFrom(scheduledExecutionResult(
                        "trigger", withRecordSpec(op -> op.hasPaidStakingRewards(List.of(Pair.of("staker", 2L)))))),
                cryptoCreate("staker").stakedNodeId(0).balance(ONE_HBAR),
                waitUntilStartOfNextStakingPeriod(1),
                doingContextual(spec -> secondBoundary.set(
                        Instant.ofEpochSecond(spec.consensusTime().getEpochSecond())
                                .plusSeconds(2 * 60))),
                // The first transaction of the first staking period the staker is eligible for rewards
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                sourcing(() -> scheduleCreate("one", cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, "staker", 1L)))
                        .waitForExpiry()
                        .expiringAt(secondBoundary.get().getEpochSecond() - 1)
                        .via("trigger")),
                waitUntilStartOfNextStakingPeriod(1),
                // The first transaction of the next staking period
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                doWithStartupConfig(
                        "consensus.handle.maxPrecedingRecords",
                        value -> sleepToExactly(secondBoundary
                                .get()
                                // The next transaction will happen one second after the time we sleep to
                                .minusSeconds(1)
                                // And we adjust the nanos so the user transaction will be in this staking
                                // period, but the triggered transaction will be in the next staking period
                                .minusNanos(Long.parseLong(value) + 1))),
                cryptoCreate("justBeforeSecondPeriod"));
    }
}
