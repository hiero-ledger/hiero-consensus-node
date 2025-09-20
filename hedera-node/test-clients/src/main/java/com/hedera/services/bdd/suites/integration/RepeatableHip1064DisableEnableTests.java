// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hedera.services.bdd.suites.integration.RepeatableHip1064Tests.validateRecordFees;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
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
public class RepeatableHip1064DisableEnableTests {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                nodeUpdate("0").declineReward(false),
                nodeUpdate("1").declineReward(false),
                nodeUpdate("2").declineReward(false),
                nodeUpdate("3").declineReward(false));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.nodeRewardsEnabled", "nodes.adjustNodeFees"})
    @Order(1)
    final Stream<DynamicTest> featureFlagDisabledThenEnabledWithAdjustFees() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        return hapiTest(
                // ----------------------------------------------------------
                // PART 1 - DISABLE NODE REWARDS
                // ----------------------------------------------------------
                overriding("nodes.nodeRewardsEnabled", "false"),
                // All nodes have decline reward set to true
                viewNode("0", node -> assertFalse(node.declineReward())),
                viewNode("1", node -> assertFalse(node.declineReward())),
                viewNode("2", node -> assertFalse(node.declineReward())),
                viewNode("3", node -> assertFalse(node.declineReward())),
                // We can update the decline reward setting even if the feature is disabled
                nodeUpdate("1").declineReward(true),
                viewNode("0", node -> assertFalse(node.declineReward())),
                viewNode("1", node -> assertTrue(node.declineReward())),
                viewNode("2", node -> assertFalse(node.declineReward())),
                viewNode("3", node -> assertFalse(node.declineReward())),
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                getTxnRecord("notFree")
                        .logged()
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount()))
                        .logged(),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 98L, 800L, 801L)),
                balanceSnapshot("Node0BeforeDisabled", "0.0.3").logged(),
                balanceSnapshot("Node1BeforeDisabled", "0.0.4").logged(),
                balanceSnapshot("Node2BeforeDisabled", "0.0.5").logged(),
                balanceSnapshot("Node3BeforeDisabled", "0.0.6").logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                sourcing(() ->
                        getAccountBalance("0.0.3").logged().hasTinyBars(changeFromSnapshot("Node0BeforeDisabled", 0))),
                sourcing(() ->
                        getAccountBalance("0.0.4").logged().hasTinyBars(changeFromSnapshot("Node1BeforeDisabled", 0))),
                sourcing(() ->
                        getAccountBalance("0.0.5").logged().hasTinyBars(changeFromSnapshot("Node2BeforeDisabled", 0))),
                sourcing(() ->
                        getAccountBalance("0.0.6").logged().hasTinyBars(changeFromSnapshot("Node3BeforeDisabled", 0))),

                // ----------------------------------------------------------
                // PART 2 - ENABLE NODE REWARDS
                // ----------------------------------------------------------
                overriding("nodes.nodeRewardsEnabled", "true"),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree1"),
                getTxnRecord("notFree1")
                        .logged()
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount()))
                        .logged(),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree1", List.of(3L, 801L)),
                doWithStartupConfig(
                        "nodes.targetYearlyNodeRewardsUsd",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                            / Integer.parseInt(numPeriods);
                                    final long targetTinybars =
                                            spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    final long prePaidRewards = expectedNodeFees.get() / 4;
                                    assertTrue(prePaidRewards > 0);
                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                }))),
                sleepForSeconds(2),
                // This is considered as one transaction submitted, so one round
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(1, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.nodeFeesCollected());
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(0)
                                    .numMissedJudgeRounds(2)
                                    .build())
                            .build();
                }),
                balanceSnapshot("Node0Before", "0.0.3").logged(),
                balanceSnapshot("Node1Before", "0.0.4").logged(),
                balanceSnapshot("Node2Before", "0.0.5").logged(),
                balanceSnapshot("Node3Before", "0.0.6").logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("somebody").payingWith(GENESIS),
                sourcing(() -> getAccountBalance("0.0.3").logged().hasTinyBars(changeFromSnapshot("Node0Before", 0))),
                sourcing(() -> getAccountBalance("0.0.4").logged().hasTinyBars(changeFromSnapshot("Node1Before", 0))),
                sourcing(() -> getAccountBalance("0.0.5")
                        .logged()
                        .hasTinyBars(changeFromSnapshot("Node2Before", expectedNodeRewards.get()))),
                sourcing(() -> getAccountBalance("0.0.6")
                        .logged()
                        .hasTinyBars(changeFromSnapshot("Node3Before", expectedNodeRewards.get()))));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.nodeRewardsEnabled", "nodes.adjustNodeFees"})
    @Order(2)
    final Stream<DynamicTest> featureFlagDisabledThenEnabledWithoutAdjustFees() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        return hapiTest(
                // ----------------------------------------------------------
                // PART 1 - DISABLE NODE REWARDS
                // ----------------------------------------------------------
                overridingTwo("nodes.nodeRewardsEnabled", "false", "nodes.adjustNodeFees", "false"),
                nodeUpdate("1").declineReward(false),
                viewNode("0", node -> assertFalse(node.declineReward())),
                viewNode("1", node -> assertFalse(node.declineReward())),
                viewNode("2", node -> assertFalse(node.declineReward())),
                viewNode("3", node -> assertFalse(node.declineReward())),
                nodeUpdate("1").declineReward(true),
                viewNode("1", node -> assertTrue(node.declineReward())),
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                getTxnRecord("notFree")
                        .logged()
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount()))
                        .logged(),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 98L, 800L, 801L)),
                balanceSnapshot("Node0BeforeDisabled", "0.0.3").logged(),
                balanceSnapshot("Node1BeforeDisabled", "0.0.4").logged(),
                balanceSnapshot("Node2BeforeDisabled", "0.0.5").logged(),
                balanceSnapshot("Node3BeforeDisabled", "0.0.6").logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                sourcing(() ->
                        getAccountBalance("0.0.3").logged().hasTinyBars(changeFromSnapshot("Node0BeforeDisabled", 0))),
                sourcing(() ->
                        getAccountBalance("0.0.4").logged().hasTinyBars(changeFromSnapshot("Node1BeforeDisabled", 0))),
                sourcing(() ->
                        getAccountBalance("0.0.5").logged().hasTinyBars(changeFromSnapshot("Node2BeforeDisabled", 0))),
                sourcing(() ->
                        getAccountBalance("0.0.6").logged().hasTinyBars(changeFromSnapshot("Node3BeforeDisabled", 0))),

                // ----------------------------------------------------------
                // PART 2 - ENABLE NODE REWARDS
                // ----------------------------------------------------------
                overriding("nodes.nodeRewardsEnabled", "true"),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree1"),
                getTxnRecord("notFree1")
                        .logged()
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount()))
                        .logged(),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree1", List.of(3L, 801L)),
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
                sleepForSeconds(2),
                // This is considered as one transaction submitted, so one round
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(1, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.nodeFeesCollected());
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(0)
                                    .numMissedJudgeRounds(2)
                                    .build())
                            .build();
                }),
                balanceSnapshot("Node0Before", "0.0.3").logged(),
                balanceSnapshot("Node1Before", "0.0.4").logged(),
                balanceSnapshot("Node2Before", "0.0.5").logged(),
                balanceSnapshot("Node3Before", "0.0.6").logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("somebody").payingWith(GENESIS),
                sourcing(() -> getAccountBalance("0.0.3").logged().hasTinyBars(changeFromSnapshot("Node0Before", 0))),
                sourcing(() -> getAccountBalance("0.0.4").logged().hasTinyBars(changeFromSnapshot("Node1Before", 0))),
                sourcing(() -> getAccountBalance("0.0.5")
                        .logged()
                        .hasTinyBars(changeFromSnapshot("Node2Before", expectedNodeRewards.get()))),
                sourcing(() -> getAccountBalance("0.0.6")
                        .logged()
                        .hasTinyBars(changeFromSnapshot("Node3Before", expectedNodeRewards.get()))));
    }
}
