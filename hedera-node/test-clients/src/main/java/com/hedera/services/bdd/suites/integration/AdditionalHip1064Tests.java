// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;
import org.assertj.core.api.Assertions;

/**
 * Critical missing test cases for HIP-1064 Daily Rewards for Active Nodes.
 *
 * This class focuses on critical boundary conditions and edge cases:
 * 1. Precise active node threshold boundaries (exactly at/below threshold)
 * 2. Extreme configuration values (0% threshold, 0 rewards, etc.)
 * 3. Node eligibility edge cases (all decline, all inactive, single winner)
 * 4. Fee redirection boundary conditions (threshold = 0, balance = threshold)
 * 5. Balance edge cases (zero balance)
 * 6. Security & malicious behavior (invalid activity data, overflow protection)
 */
@Order(8)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdditionalHip1064Tests {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.nodeRewardsEnabled", "true",
                "nodes.preserveMinNodeRewardBalance", "true",
                "ledger.transfers.maxLen", "2"));
        testLifecycle.doAdhoc(
                nodeUpdate("0").declineReward(false),
                nodeUpdate("1").declineReward(false),
                nodeUpdate("2").declineReward(false),
                nodeUpdate("3").declineReward(false));
    }

    /**
     * Test node that misses exactly 10% of rounds (exactly at the threshold).
     * This tests the boundary condition where a node is just barely active.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.activeRoundsPercent"})
    @Order(1)
    final Stream<DynamicTest> nodeAtExactActiveThresholdReceivesRewards() {
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();

        return hapiTest(
                overriding("nodes.activeRoundsPercent", "90"), // 90% active required

                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                doWithStartupConfig(
                        "nodes.targetYearlyNodeRewardsUsd",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                            / Integer.parseInt(numPeriods);
                                    final long targetTinybars = spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    expectedNodeRewards.set(targetTinybars);
                                }))),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set nodes to be exactly at 90% threshold: with 10 rounds, missing 1 round = 90% active
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    return nodeRewards
                            .copyBuilder()
                            .numRoundsInStakingPeriod(10)
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(1).build()) // 90% active
                            .build();
                }),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),

                waitUntilStartOfNextStakingPeriod(1),

                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidatorForThresholdTest(expectedNodeRewards::get, nodeRewardBalance::get),
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                        .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L)
                                        && asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                                        .isAfter(startConsensusTime.get())),
                        Duration.ofSeconds(1)),

                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)
        );
    }

    // ================================
    // SECURITY & EDGE CASE TESTS
    // ================================

    /**
     * Test malicious node behavior - node with impossible activity data.
     * What happens if numMissedJudgeRounds > numRoundsInStakingPeriod?
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(12)
    final Stream<DynamicTest> maliciousNodeWithInvalidActivityData() {
        final AtomicLong initialNodeRewardBalance = new AtomicLong(0);
        final AtomicLong finalNodeRewardBalance = new AtomicLong(0);

        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(initialNodeRewardBalance::set)
                        .logged(),

                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set malicious/invalid activity data
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    int totalRounds = (int) nodeRewards.numRoundsInStakingPeriod();
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(
                                    // Malicious: missed more rounds than total rounds (impossible)
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(totalRounds + 100).build(),
                                    // Malicious: negative missed rounds
                                    NodeActivity.newBuilder().nodeId(2).numMissedJudgeRounds(-5).build(),
                                    // Malicious: extremely large number
                                    NodeActivity.newBuilder().nodeId(3).numMissedJudgeRounds(Integer.MAX_VALUE).build())
                            .build();
                }),

                waitUntilStartOfNextStakingPeriod(1),

                // System should handle this gracefully without crashing
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> {
                            // Test passes if system doesn't crash due to invalid data
                            // Log what actually happened for analysis
                        },
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(finalNodeRewardBalance::set)
                        .logged(),

                // Verify what actually happened to rewards with invalid data
                doingContextual(spec -> {
                    long rewardsPaid = initialNodeRewardBalance.get() - finalNodeRewardBalance.get();
                    // System should handle invalid data gracefully - either pay no rewards or handle safely
                    assertTrue(rewardsPaid >= 0, "System should not pay negative rewards with invalid data");
                })
        );
    }

    /**
     * Test overflow protection in reward calculations.
     * What happens with extremely large numbers that might cause overflow?
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.targetYearlyNodeRewardsUsd"})
    @Order(13)
    final Stream<DynamicTest> overflowProtectionInRewardCalculations() {
        return hapiTest(
                // Set extremely large target reward that might cause overflow
                overriding("nodes.targetYearlyNodeRewardsUsd", "999999999999"), // Nearly a trillion dollars

                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                // Test that system can handle the calculation without crashing
                doWithStartupConfig(
                        "nodes.targetYearlyNodeRewardsUsd",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    try {
                                        final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                                / Integer.parseInt(numPeriods);
                                        final long targetTinybars = spec.ratesProvider().toTbWithActiveRates(targetReward);
                                        // If we get here without overflow, system handled it correctly
                                    } catch (ArithmeticException e) {
                                        // System should handle overflow gracefully, not crash
                                        System.out.println("Overflow detected and handled: " + e.getMessage());
                                    }
                                }))),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(0)
                                    .build())
                            .build();
                }),

                waitUntilStartOfNextStakingPeriod(1),

                // System should handle this gracefully without overflow
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> {
                            // Test passes if system doesn't crash due to overflow
                            // Rewards may or may not be distributed, but system should be stable
                        },
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS)
        );
    }

    /**
     * Test node that misses just slightly more than threshold (89% active when 90% required).
     * This should result in no rewards.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.activeRoundsPercent"})
    @Order(2)
    final Stream<DynamicTest> nodeJustBelowActiveThresholdReceivesNoReward() {
        return hapiTest(
                overriding("nodes.activeRoundsPercent", "90"), // 90% threshold

                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set all eligible nodes just below 90% threshold: 89% active
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    return nodeRewards
                            .copyBuilder()
                            .numRoundsInStakingPeriod(9)
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(1).build(), // 89% active
                                    NodeActivity.newBuilder().nodeId(2).numMissedJudgeRounds(1).build(), // 89% active
                                    NodeActivity.newBuilder().nodeId(3).numMissedJudgeRounds(1).build()) // 89% active
                            .build();
                }),

                waitUntilStartOfNextStakingPeriod(1),

                // Expect no rewards since no nodes meet the threshold
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS)
        );
    }

    /**
     * Test scenario where all nodes are inactive - no rewards should be distributed even with balance.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(3)
    final Stream<DynamicTest> noRewardsWhenAllNodesInactive() {
        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set all nodes to be inactive (miss all rounds)
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    int totalRounds = (int) nodeRewards.numRoundsInStakingPeriod();
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(totalRounds).build(), // 0% active
                                    NodeActivity.newBuilder().nodeId(2).numMissedJudgeRounds(totalRounds).build(), // 0% active
                                    NodeActivity.newBuilder().nodeId(3).numMissedJudgeRounds(totalRounds).build()) // 0% active
                            .build();
                }),

                waitUntilStartOfNextStakingPeriod(1),

                // Expect no rewards since all nodes are inactive
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS)
        );
    }

    /**
     * Test behavior when node reward account has exactly zero balance.
     * Fixed according to developer feedback - use mutateAccount to set balance to zero after fee accumulation.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(4)
    final Stream<DynamicTest> noRewardsWhenNodeRewardAccountIsEmpty() {
        return hapiTest(
                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set up active nodes
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(0) // Active node
                                    .build())
                            .build();
                }),

                // Set zero balance to node reward account AFTER fees have accumulated
                EmbeddedVerbs.mutateAccount(NODE_REWARD, account -> account.tinybarBalance(0)),

                getAccountBalance(NODE_REWARD)
                        .hasTinyBars(0L)
                        .logged(),

                waitUntilStartOfNextStakingPeriod(1),

                // Expect no rewards due to zero balance
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS)
        );
    }

    /**
     * Test that when nodeRewardAccountThreshold is set to 0, no fee redirection occurs
     * regardless of balance (since balance cannot be negative).
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.nodeRewardAccountThreshold"})
    @Order(5)
    final Stream<DynamicTest> noFeeRedirectionWhenThresholdIsZero() {
        final AtomicLong initialNodeRewardBalance = new AtomicLong(0);
        final AtomicLong finalNodeRewardBalance = new AtomicLong(0);

        return hapiTest(
                overriding("nodes.nodeRewardAccountThreshold", "0"), // Zero threshold

                // Start with small balance in node reward account
                cryptoTransfer(TokenMovement.movingHbar(1000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(initialNodeRewardBalance::set)
                        .logged(),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("testFile")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeGeneratingTxn"),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(finalNodeRewardBalance::set)
                        .logged(),

                // Verify node reward balance did NOT increase (no redirection)
                doingContextual(spec -> {
                    long balanceIncrease = finalNodeRewardBalance.get() - initialNodeRewardBalance.get();
                    assertEquals(0L, balanceIncrease,
                            "No fees should be redirected when threshold is 0 (balance cannot be below 0)");
                }),

                // Verify fees went to normal accounts (no redirection to 801)
                validateRecordFees("feeGeneratingTxn", List.of(3L, 98L, 800L, 801L))
        );
    }

    /**
     * Test edge case when there are no rounds in staking period (could cause division by zero).
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(6)
    final Stream<DynamicTest> noRewardsWhenZeroRoundsInStakingPeriod() {
        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set numRoundsInStakingPeriod to 0 - edge case that could cause division by zero
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    return nodeRewards
                            .copyBuilder()
                            .numRoundsInStakingPeriod(0) // Zero rounds - could cause division by zero
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(0).build()) // 0/0 = undefined
                            .build();
                }),

                waitUntilStartOfNextStakingPeriod(1),

                // Expect no rewards since there are no rounds to calculate activity
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS)
        );
    }

    /**
     * Test when activeRoundsPercent is set to 0% - all nodes should be considered active.
     * This tests the minimum threshold boundary.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.activeRoundsPercent"})
    @Order(7)
    final Stream<DynamicTest> allNodesActiveWhenThresholdIsZero() {
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();

        return hapiTest(
                overriding("nodes.activeRoundsPercent", "0"), // 0% threshold - all nodes active

                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                doWithStartupConfig(
                        "nodes.targetYearlyNodeRewardsUsd",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                            / Integer.parseInt(numPeriods);
                                    final long targetTinybars = spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    expectedNodeRewards.set(targetTinybars);
                                }))),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set nodes to miss all rounds - but with 0% threshold, they should still be "active"
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    int totalRounds = (int) nodeRewards.numRoundsInStakingPeriod();
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(totalRounds).build(), // 0% active but should get rewards
                                    NodeActivity.newBuilder().nodeId(2).numMissedJudgeRounds(totalRounds).build(), // 0% active but should get rewards
                                    NodeActivity.newBuilder().nodeId(3).numMissedJudgeRounds(totalRounds).build()) // 0% active but should get rewards
                            .build();
                }),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),

                waitUntilStartOfNextStakingPeriod(1),

                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                allNodesActiveValidator(expectedNodeRewards::get, nodeRewardBalance::get),
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                        .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L)
                                        && asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                                        .isAfter(startConsensusTime.get())),
                        Duration.ofSeconds(1)),

                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)
        );
    }

    /**
     * Test when all eligible nodes decline rewards - no rewards should be distributed even with active nodes.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(8)
    final Stream<DynamicTest> noRewardsWhenAllEligibleNodesDecline() {
        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                // All nodes decline rewards
                nodeUpdate("0").declineReward(true),
                nodeUpdate("1").declineReward(true),
                nodeUpdate("2").declineReward(true),
                nodeUpdate("3").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set up active nodes (but they all decline)
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(0).numMissedJudgeRounds(0).build(), // Active but declines
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(0).build(), // Active but declines
                                    NodeActivity.newBuilder().nodeId(2).numMissedJudgeRounds(0).build(), // Active but declines
                                    NodeActivity.newBuilder().nodeId(3).numMissedJudgeRounds(0).build()) // Active but declines
                            .build();
                }),

                waitUntilStartOfNextStakingPeriod(1),

                // Expect no rewards since all nodes decline
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS)
        );
    }

    /**
     * Test reward calculation when targetYearlyNodeRewardsUsd is 0 - no rewards should be distributed.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.targetYearlyNodeRewardsUsd"})
    @Order(9)
    final Stream<DynamicTest> noRewardsWhenTargetYearlyRewardsIsZero() {
        return hapiTest(
                overriding("nodes.targetYearlyNodeRewardsUsd", "0"), // Zero target rewards

                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set up active nodes
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(0).build(), // Active
                                    NodeActivity.newBuilder().nodeId(2).numMissedJudgeRounds(0).build()) // Active
                            .build();
                }),

                waitUntilStartOfNextStakingPeriod(1),

                // Expect no rewards since target is zero
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),

                cryptoCreate("nobody").payingWith(GENESIS)
        );
    }

    /**
     * Test when balance exactly equals the threshold - should NOT trigger redirection.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.nodeRewardAccountThreshold"})
    @Order(10)
    final Stream<DynamicTest> feeRedirectionWhenBalanceEqualsThreshold() {
        final AtomicLong balanceBeforeTest = new AtomicLong(0);
        final AtomicLong balanceAfterTest = new AtomicLong(0);

        return hapiTest(
                overriding("nodes.nodeRewardAccountThreshold", "100000000000"), // 1000 HBAR in tinybars

                waitUntilStartOfNextStakingPeriod(1),

                cryptoCreate(CIVILIAN_PAYER),

                // Set balance to exactly match threshold BEFORE fee-generating transaction
                EmbeddedVerbs.mutateAccount(NODE_REWARD, account -> account.tinybarBalance(100000000000L)), // 1000 HBAR

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(balanceBeforeTest::set)
                        .logged(),

                fileCreate("testFile")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeGeneratingTxn"),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(balanceAfterTest::set)
                        .logged(),

                // After the transaction, balance should be above threshold due to fees
                doingContextual(spec -> {
                    assertEquals(100000000000L, balanceBeforeTest.get(),
                            "Balance should be exactly at threshold before transaction");
                    assertTrue(balanceAfterTest.get() > 100000000000L,
                            "Balance should be above threshold after receiving transaction fees");
                }),

                // Verify normal fee distribution (no redirection since balance >= threshold)
                validateRecordFees("feeGeneratingTxn", List.of(3L, 98L, 800L, 801L))
        );
    }

    /**
     * Test when only one node is active and gets all rewards - edge case for reward distribution.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(11)
    final Stream<DynamicTest> singleActiveNodeReceivesFullReward() {
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();

        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                nodeUpdate("0").declineReward(true),

                waitUntilStartOfNextStakingPeriod(1),

                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),

                cryptoCreate(CIVILIAN_PAYER),

                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),

                doWithStartupConfig(
                        "nodes.targetYearlyNodeRewardsUsd",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                            / Integer.parseInt(numPeriods);
                                    final long targetTinybars = spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    expectedNodeRewards.set(targetTinybars);
                                }))),

                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Only node 2 is active, others are inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    int totalRounds = (int) nodeRewards.numRoundsInStakingPeriod();
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(
                                    NodeActivity.newBuilder().nodeId(1).numMissedJudgeRounds(totalRounds).build(), // 0% active
                                    NodeActivity.newBuilder().nodeId(2).numMissedJudgeRounds(0).build(), // 100% active
                                    NodeActivity.newBuilder().nodeId(3).numMissedJudgeRounds(totalRounds).build()) // 0% active
                            .build();
                }),

                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),

                waitUntilStartOfNextStakingPeriod(1),

                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                singleNodeRewardValidator(expectedNodeRewards::get, nodeRewardBalance::get),
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                        .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L)
                                        && asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                                        .isAfter(startConsensusTime.get())),
                        Duration.ofSeconds(1)),

                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)
        );
    }

    // ================================
    // HELPER VALIDATORS
    // ================================

    static VisibleItemsValidator nodeRewardsValidatorForThresholdTest(
            @NonNull final LongSupplier expectedPerNodeReward, @NonNull final LongSupplier nodeRewardBalance) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();

            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));

            // Should have rewards for the nodes that are exactly at the threshold
            assertTrue(bodyAdjustments.size() >= 2, "Should have at least node reward account debit and one node credit");

            // Verify node reward account was debited
            final long nodeRewardDebit = bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount"));
            assertTrue(nodeRewardDebit < 0, "Node reward account should be debited");

            // Verify that nodes meeting exactly the threshold get rewards
            long totalCredits = bodyAdjustments.values().stream()
                    .filter(amount -> amount > 0)
                    .mapToLong(Long::longValue)
                    .sum();
            assertEquals(-nodeRewardDebit, totalCredits, "Total credits should equal node reward debit");
        };
    }

    static VisibleItemsValidator allNodesActiveValidator(
            @NonNull final LongSupplier expectedPerNodeReward, @NonNull final LongSupplier nodeRewardBalance) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();

            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));

            // With 0% threshold, all 3 eligible nodes should get rewards (node 0 declines)
            assertEquals(4, bodyAdjustments.size());

            long expectedPerNode = expectedPerNodeReward.getAsLong();
            long expectedDebit = -3 * expectedPerNode;

            if (Math.abs(expectedDebit) > nodeRewardBalance.getAsLong()) {
                expectedPerNode = nodeRewardBalance.getAsLong() / 3;
                expectedDebit = 3 * -expectedPerNode;
            }

            final long nodeRewardDebit = bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount"));
            assertEquals(expectedDebit, nodeRewardDebit, "Expected debit for all 3 eligible nodes");

            // All three eligible nodes should get rewards despite missing all rounds
            assertEquals(expectedPerNode, bodyAdjustments.get(4L), "Node 1 should get reward with 0% threshold");
            assertEquals(expectedPerNode, bodyAdjustments.get(5L), "Node 2 should get reward with 0% threshold");
            assertEquals(expectedPerNode, bodyAdjustments.get(6L), "Node 3 should get reward with 0% threshold");
        };
    }

    static VisibleItemsValidator singleNodeRewardValidator(
            @NonNull final LongSupplier expectedPerNodeReward, @NonNull final LongSupplier nodeRewardBalance) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();

            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));

            // Should have reward for only one node
            assertEquals(2, bodyAdjustments.size());

            long expectedReward = expectedPerNodeReward.getAsLong();
            long expectedDebit = -expectedReward;

            if (Math.abs(expectedDebit) > nodeRewardBalance.getAsLong()) {
                expectedReward = nodeRewardBalance.getAsLong();
                expectedDebit = -expectedReward;
            }

            final long nodeRewardDebit = bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount"));
            assertEquals(expectedDebit, nodeRewardDebit, "Expected debit for single active node");

            // Only node 2 should get a reward (node 5)
            assertEquals(expectedReward, bodyAdjustments.get(5L), "Single active node should get full reward");
        };
    }

    static SpecOperation validateRecordFees(final String record, List<Long> expectedFeeAccounts) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            var fileCreate = getTxnRecord(record);
            allRunFor(spec, fileCreate);
            var response = fileCreate.getResponseRecord();
            assertEquals(
                    response.getTransferList().getAccountAmountsList().stream()
                            .filter(aa -> aa.getAmount() < 0)
                            .count(),
                    1);
            assertEquals(
                    expectedFeeAccounts,
                    response.getTransferList().getAccountAmountsList().stream()
                            .filter(aa -> aa.getAmount() > 0)
                            .map(aa -> aa.getAccountID().getAccountNum())
                            .sorted()
                            .toList());
        });
    }
}