// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
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
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

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

    // ================================
    // PRECISE ACTIVE NODE THRESHOLD BOUNDARY TESTS
    // ================================
    // Existing tests show 0% active (3/3 missed), but don't test the exact threshold boundary

    /**
     * Test node that misses exactly 10% of rounds (exactly at the default threshold).
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
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidatorForThresholdTest(expectedNodeRewards::get, nodeRewardBalance::get),
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                                .anyMatch(
                                                        aa -> aa.getAccountID().getAccountNum() == 801L
                                                                && aa.getAmount() < 0L)
                                        && asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                                                .isAfter(startConsensusTime.get())),
                        Duration.ofSeconds(1)),
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                nodeUpdate("0").declineReward(true),
                waitUntilStartOfNextStakingPeriod(1),
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
                                    final long targetTinybars =
                                            spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    expectedNodeRewards.set(targetTinybars);
                                }))),
                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),

                // Set nodes to be exactly at 90% threshold: with 10 rounds, missing 1 round = 90% active
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    // Assuming we can get to 10 rounds for cleaner math
                    return nodeRewards
                            .copyBuilder()
                            .numRoundsInStakingPeriod(10)
                            .nodeActivities(
                                    NodeActivity.newBuilder()
                                            .nodeId(1)
                                            .numMissedJudgeRounds(1)
                                            .build(), // 90% active
                                    NodeActivity.newBuilder()
                                            .nodeId(2)
                                            .numMissedJudgeRounds(1)
                                            .build()) // 90% active
                            .build();
                }),
                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
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

                // Expect no rewards since no nodes meet the threshold
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),
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
                                    NodeActivity.newBuilder()
                                            .nodeId(1)
                                            .numMissedJudgeRounds(1)
                                            .build(), // 89% active
                                    NodeActivity.newBuilder()
                                            .nodeId(2)
                                            .numMissedJudgeRounds(1)
                                            .build(), // 89% active
                                    NodeActivity.newBuilder()
                                            .nodeId(3)
                                            .numMissedJudgeRounds(1)
                                            .build()) // 89% active
                            .build();
                }),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS));
    }

    /**
     * Test scenario where all nodes are inactive - no rewards should be distributed even with balance.
     * This is different from existing tests which always have some active nodes.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(3)
    final Stream<DynamicTest> noRewardsWhenAllNodesInactive() {
        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),

                // Expect no rewards since all nodes are inactive
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),
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
                                    NodeActivity.newBuilder()
                                            .nodeId(1)
                                            .numMissedJudgeRounds(totalRounds)
                                            .build(), // 0% active
                                    NodeActivity.newBuilder()
                                            .nodeId(2)
                                            .numMissedJudgeRounds(totalRounds)
                                            .build(), // 0% active
                                    NodeActivity.newBuilder()
                                            .nodeId(3)
                                            .numMissedJudgeRounds(totalRounds)
                                            .build()) // 0% active
                            .build();
                }),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS));
    }

    /**
     * Test behavior when node reward account has exactly zero balance.
     * This is different from the existing test that uses 10 HBAR - tests absolute edge case.
     * Should not produce any reward transactions.
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(4)
    final Stream<DynamicTest> noRewardsWhenNodeRewardAccountIsEmpty() {
        return hapiTest(
                // Don't fund the node reward account - it should have zero balance

                // Expect no rewards due to zero balance
                recordStreamMustIncludeNoFailuresWithoutBackgroundTrafficFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 being debited!"),
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),
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

                // Verify zero balance
                getAccountBalance(NODE_REWARD).hasTinyBars(0L).logged(),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS));
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
            assertTrue(
                    bodyAdjustments.size() >= 2, "Should have at least node reward account debit and one node credit");

            long expectedPerNode = expectedPerNodeReward.getAsLong();

            // Verify node reward account was debited
            final long nodeRewardDebit =
                    bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount"));
            assertTrue(nodeRewardDebit < 0, "Node reward account should be debited");

            // Verify that nodes meeting exactly the threshold get rewards
            long totalCredits = bodyAdjustments.values().stream()
                    .filter(amount -> amount > 0)
                    .mapToLong(Long::longValue)
                    .sum();
            assertEquals(-nodeRewardDebit, totalCredits, "Total credits should equal node reward debit");
        };
    }
}
