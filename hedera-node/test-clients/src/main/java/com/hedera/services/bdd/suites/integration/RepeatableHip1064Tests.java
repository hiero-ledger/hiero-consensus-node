// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_STAKE_UPDATE;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.junit.support.BlockStreamAccess.blockFrom;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.hiero.base.concurrent.interrupt.Uninterruptable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@Order(6)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RepeatableHip1064Tests {
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

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(1)
    final Stream<DynamicTest> rewardsDontExceedRewardAccountBalance() {
        final AtomicLong preCollectionNodeFees = new AtomicLong(0);
        final AtomicLong additionalNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidator(expectedNodeRewards::get, nodeRewardBalance::get),
                                // We expect two node rewards payments in this test.
                                // But first staking period all nodes are inactive and minReward is 0.
                                // So no synthetic node rewards payment is expected.
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                                .anyMatch(
                                                        aa -> aa.getAccountID().getAccountNum() == 801L
                                                                && aa.getAmount() < 0L)
                                        && asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                                                .isAfter(startConsensusTime.get())),
                        Duration.ofSeconds(1)),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                nodeUpdate("0").declineReward(true),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // First get any node fees already collected at the end of this block
                sleepForBlockPeriod(),
                cryptoCreate(CIVILIAN_PAYER),
                EmbeddedVerbs.<NodeRewards>viewSingleton(
                        TokenService.NAME,
                        "NODE_REWARDS",
                        (nodeRewards) -> preCollectionNodeFees.set(nodeRewards.nodeFeesCollected())),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                // Validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 801L)),
                // Get the additional fee node fee collected
                getTxnRecord("notFree")
                        .exposingTo(r -> additionalNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                doWithStartupConfig(
                        "nodes.targetYearlyNodeRewardsUsd",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                            / Integer.parseInt(numPeriods);
                                    final long targetTinybars =
                                            spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    final long prePaidRewards =
                                            (preCollectionNodeFees.get() + additionalNodeFees.get()) / 4;
                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                }))),
                sleepForBlockPeriod(),
                // This is considered as one transaction submitted, so one round
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(3, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    final long expectedNodeFees = preCollectionNodeFees.get() + additionalNodeFees.get();
                    assertEquals(
                            expectedNodeFees, nodeRewards.nodeFeesCollected(), "Node fees collected did not match");
                    // Update node 1 to have missed more than 10% of rounds
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(3)
                                    .build())
                            .build();
                }),
                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Given,
     * <ol>
     *     <li>All nodes except {@code node0} have non-system accounts; So node0 will declineRewards and,</li>
     *     <li>All nodes except {@code node1} were active in a period {@code P}; and,</li>
     *     <li>Fees of amount {@code C} were collected by node accounts in {@code P}; and,</li>
     *     <li>The target node reward payment for 365 periods in USD is {@code T}.</li>
     * </ol>
     * Then, at the start of period {@code P+1},
     * <ol>
     *     <li>{@code node2} and {@code node3} each receive {@code (T in tinybar) / 365 - (C / 4)}; and,</li>
     *     <li>Neither {@code node0} and {@code node1} receive any rewards.</li>
     * </ol>
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @Order(2)
    final Stream<DynamicTest> paysAdjustedFeesToAllEligibleActiveAccountsAtStartOfNewPeriod() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidator(expectedNodeRewards::get, nodeRewardBalance::get),
                                // We expect two node rewards payments in this test.
                                // But first staking period all nodes are inactive and minReward is 0.
                                // So no synthetic node rewards payment is expected.
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
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                // Collects ~1.8M tinybar in node fees; so ~450k tinybar per node
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 801L)),
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
                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                }))),
                sleepForBlockPeriod(),
                // This is considered as one transaction submitted, so one round
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(3, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.nodeFeesCollected());
                    // Update node 1 to have missed more than 10% of rounds
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(3)
                                    .build())
                            .build();
                }),
                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Given,
     * <ol>
     *     <li>All nodes except {@code node0} have non-system accounts; and,</li>
     *     <li>All nodes except {@code node1} were active in a period {@code P}; and,</li>
     *     <li>Fees of amount {@code C} were collected by node accounts in {@code P}; and,</li>
     *     <li>The target node reward payment for 365 periods in USD is {@code T}.</li>
     * </ol>
     * Then, at the start of period {@code P+1},
     * <ol>
     *     <li>{@code node2} and {@code node3} each receive {@code (T in tinybar) / 365 - (C / 4)}; and,</li>
     *     <li>{@code node1} receive minimum node reward.</li>
     *     <li>{@code node0} doesnt receive any rewards.</li>
     * </ol>
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.minPerPeriodNodeRewardUsd"})
    @Order(3)
    final Stream<DynamicTest> inactiveNodesPaidWhenMinRewardsGreaterThanZero() {
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong expectedMinNodeReward = new AtomicLong(0);
        return hapiTest(
                overriding("nodes.minPerPeriodNodeRewardUsd", "10"),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidatorWithInactiveNodes(
                                        expectedNodeRewards::get, expectedMinNodeReward::get),
                                // We expect two node rewards payments in this test.
                                // First staking period all nodes are inactive and minReward is 10.
                                // Second staking period, two nodes are active and one node is inactive
                                2,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                                .anyMatch(
                                                        aa -> aa.getAccountID().getAccountNum() == 801L
                                                                && aa.getAmount() < 0L)
                                        && asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                                                .isAfter(startConsensusTime.get())),
                        Duration.ofSeconds(1)),
                nodeUpdate("0").declineReward(true),
                cryptoTransfer(TokenMovement.movingHbar(10000000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                // Collects ~1.8M tinybar in node fees; so ~450k tinybar per node
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 98L, 800L, 801L)),
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
                                    final long minRewardTinybars = spec.ratesProvider()
                                            .toTbWithActiveRates((10L * 100 * TINY_PARTS_PER_WHOLE));

                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                    expectedMinNodeReward.set(minRewardTinybars);
                                }))),
                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(3, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.nodeFeesCollected());
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(3)
                                    .build())
                            .build();
                }),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Given,
     * <ol>
     *     <li>All nodes except {@code node0} have non-system accounts; So node0 will declineRewards and,</li>
     *     <li>All nodes except {@code node1} were active in a period {@code P}; and,</li>
     *     <li>Fees of amount {@code C} were collected by node accounts in {@code P}; and,</li>
     *     <li>The target node reward payment for 365 periods in USD is {@code T}.</li>
     * </ol>
     * Then, at the start of period {@code P+1},
     * <ol>
     *     <li>{@code node2} and {@code node3} each receive {@code (T in tinybar) / 365 - (C / 4)}; and,</li>
     *     <li>Neither {@code node0} and {@code node1} receive any rewards.</li>
     * </ol>
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.adjustNodeFees"})
    @Order(4)
    final Stream<DynamicTest> paysNonAdjustedFeesToAllEligibleActiveAccountsAtStartOfNewPeriod() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                overriding("nodes.adjustNodeFees", "false"),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidator(expectedNodeRewards::get, nodeRewardBalance::get),
                                // We expect two node rewards payments in this test.
                                // But first staking period all nodes are inactive and minReward is 0.
                                // So no synthetic node rewards payment is expected.
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
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                getAccountInfo(NODE_REWARD).logged(),
                // Collects ~1.8M tinybar in node fees; so ~450k tinybar per node
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                doWithStartupConfig(
                        "nodes.targetYearlyNodeRewardsUsd",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                            / Integer.parseInt(numPeriods);
                                    final long targetTinybars =
                                            spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    // node fees are not deducted
                                    final long prePaidRewards = 0;
                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                }))),
                sleepForBlockPeriod(),
                // This is considered as one transaction submitted, so one round
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(3, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.nodeFeesCollected());
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(3)
                                    .build())
                            .build();
                }),
                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.preserveMinNodeRewardBalance"})
    @Order(5)
    final Stream<DynamicTest> preserveNodeRewardBalanceHasEffectWhenFeatureEnabled() {
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        return hapiTest(
                overriding("nodes.preserveMinNodeRewardBalance", "false"),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidator(expectedNodeRewards::get, nodeRewardBalance::get),
                                // We expect two node rewards payments in this test.
                                // But first staking period all nodes are inactive and minReward is 0.
                                // So no synthetic node rewards payment is expected.
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
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                // Collects ~1.8M tinybar in node fees; so ~450k tinybar per node
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 98L, 800L, 801L)),
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
                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                }))),
                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(3, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.nodeFeesCollected());
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(3)
                                    .build())
                            .build();
                }),
                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"nodes.minNodeRewardBalance"})
    @Order(6)
    final Stream<DynamicTest> distributesFeesWhenRewardBalanceIsHigh() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                overriding("nodes.minNodeRewardBalance", "1000000000000"),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsValidator(expectedNodeRewards::get, nodeRewardBalance::get),
                                // We expect two node rewards payments in this test.
                                // But first staking period all nodes are inactive and minReward is 0.
                                // So no synthetic node rewards payment is expected.
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
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                // Collects ~1.8M tinybar in node fees; so ~450k tinybar per node
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 98L, 800L, 801L)),
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
                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                }))),
                sleepForBlockPeriod(),
                // This is considered as one transaction submitted, so one round
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(3, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.nodeFeesCollected());
                    // Update node 1 to have missed more than 10% of rounds
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(3)
                                    .build())
                            .build();
                }),
                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @Order(7)
    @RepeatableHapiTest(value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    Stream<DynamicTest> nodeRewardPaymentsAlsoTriggersStakePeriodBoundarySideEffects() {
        return hapiTest(
                waitUntilStartOfNextStakingPeriod(1),
                nodeUpdate("0").declineReward(false),
                sleepForBlockPeriod(),
                // Hack to make nodes appear active
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> nodeRewards
                        .copyBuilder()
                        .nodeActivities(List.of())
                        .build()),
                cryptoTransfer(tinyBarsFromTo(GENESIS, NODE_REWARD, ONE_MILLION_HBARS)),
                // Move into a new staking period
                waitUntilStartOfNextStakingPeriod(1),
                // Close a block whose only chance of exporting a NodeStakeUpdate is the node reward payment
                doingContextual(spec -> spec.repeatableEmbeddedHederaOrThrow().handleRoundWithNoUserTransactions()),
                sleepForBlockPeriod(),
                doingContextual(spec -> spec.repeatableEmbeddedHederaOrThrow().handleRoundWithNoUserTransactions()),
                sleepForBlockPeriod(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                exposeLatestBlock(
                        b -> {
                            final var rewardPayment = findFirst(b, CRYPTO_TRANSFER);
                            assertTrue(
                                    rewardPayment.isPresent(), "Node rewards payment should be present in the block");
                            final var rewardPaymentTx =
                                    rewardPayment.orElseThrow().body().cryptoTransferOrThrow();
                            final var hasNodeRewardDebit =
                                    requireNonNull(rewardPaymentTx.transfers()).accountAmounts().stream()
                                            .anyMatch(aa -> aa.amount() < 0);
                            assertTrue(hasNodeRewardDebit, "Node rewards payment should be present in the block");
                            final var nodeStakeUpdate = findFirst(b, NODE_STAKE_UPDATE);
                            assertTrue(nodeStakeUpdate.isPresent(), "Node stake update should be present in the block");
                        },
                        Duration.ofSeconds(1)));
    }

    private static Optional<TransactionParts> findFirst(
            @NonNull final Block b, @NonNull final HederaFunctionality function) {
        return b.items().stream()
                .filter(BlockItem::hasSignedTransaction)
                .map(BlockItem::signedTransactionOrThrow)
                .map(TransactionParts::from)
                .filter(parts -> parts.function() == function)
                .findFirst();
    }

    static SpecOperation exposeLatestBlock(Consumer<Block> cb, Duration after) {
        return UtilVerbs.doingContextual((spec) -> {
            Uninterruptable.tryToSleep(after);
            try (final var stream = Files.walk(spec.getNetworkNodes().getFirst().getExternalPath(BLOCK_STREAMS_DIR))) {
                final var lastPath = stream.filter(BlockStreamAccess::isBlockFile)
                        .peek(System.out::println)
                        .max(comparing(BlockStreamAccess::extractBlockNumber))
                        .orElseThrow();
                cb.accept(blockFrom(lastPath));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static SpecOperation validateRecordFees(final String record, List<Long> expectedFeeAccounts) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            var fileCreate = getTxnRecord(record);
            allRunFor(spec, fileCreate);
            var response = fileCreate.getResponseRecord();
            assertEquals(
                    1,
                    response.getTransferList().getAccountAmountsList().stream()
                            .filter(aa -> aa.getAmount() < 0)
                            .count());
            // When the feature is disabled the node fees go to node. Network fee is split between 98, 800 and 801
            assertEquals(
                    expectedFeeAccounts,
                    response.getTransferList().getAccountAmountsList().stream()
                            .filter(aa -> aa.getAmount() > 0)
                            .map(aa -> aa.getAccountID().getAccountNum())
                            .sorted()
                            .toList());
        });
    }

    static VisibleItemsValidator nodeRewardsValidator(
            @NonNull final LongSupplier expectedPerNodeReward, @NonNull final LongSupplier nodeRewardBalance) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            long expectedPerNode = expectedPerNodeReward.getAsLong();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            assertEquals(3, bodyAdjustments.size());
            // node2 and node3 only expected to receive (node0 is system, node1 was inactive)
            long expectedDebit = -2 * expectedPerNode;
            if (Math.abs(expectedDebit) > nodeRewardBalance.getAsLong()) {
                expectedPerNode = nodeRewardBalance.getAsLong() / 2;
                expectedDebit = 2 * -expectedPerNode;
            }
            final long nodeRewardDebit =
                    bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount"));
            assertEquals(
                    expectedDebit,
                    nodeRewardDebit,
                    "Expected node reward debit was " + expectedDebit + ", but was " + nodeRewardDebit
                            + " (expectedPerNode = " + expectedPerNode + ")");
            // node2 credit
            assertEquals(expectedPerNode, bodyAdjustments.get(5L), "Node 2 reward is not as expected");
            // node3 credit
            assertEquals(expectedPerNode, bodyAdjustments.get(6L), "Node 3 reward is not as expected");
        };
    }

    static VisibleItemsValidator nodeRewardsValidatorWithInactiveNodes(
            @NonNull final LongSupplier expectedPerNodeReward, @NonNull final LongSupplier expectedMinNodeReward) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(2, items.size());

            final var firstRecord = items.getFirst();
            final var secondRecord = items.entries().get(1);

            assertEquals(CryptoTransfer, firstRecord.function());
            assertEquals(CryptoTransfer, secondRecord.function());

            validateFirstRecord(spec, firstRecord, expectedMinNodeReward);
            validateSecondRecord(spec, secondRecord, expectedPerNodeReward, expectedMinNodeReward);
        };
    }

    private static void validateSecondRecord(
            final HapiSpec spec,
            final RecordStreamEntry secondRecord,
            final LongSupplier expectedPerNodeReward,
            final LongSupplier expectedMinNodeReward) {
        final var op = secondRecord.body().getCryptoTransfer();
        final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
        assertEquals(4, bodyAdjustments.size());
        // node2 and node3 and node1 (inactive) will receive rewards
        final long expectedDebit = -2 * expectedPerNodeReward.getAsLong() - expectedMinNodeReward.getAsLong();
        assertEquals(
                expectedDebit, bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount")));
        // node2 credit is active reward as it is active
        assertEquals(expectedPerNodeReward.getAsLong(), bodyAdjustments.get(5L));
        // node3 credit is active reward as it is active
        assertEquals(expectedPerNodeReward.getAsLong(), bodyAdjustments.get(6L));
        // node1 credit is min reward as it is inactive
        assertEquals(expectedMinNodeReward.getAsLong(), bodyAdjustments.get(4L));
    }

    private static void validateFirstRecord(
            final HapiSpec spec, final RecordStreamEntry firstRecord, final LongSupplier expectedMinNodeReward) {
        final var op = firstRecord.body().getCryptoTransfer();
        final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
        assertEquals(4, bodyAdjustments.size());
        // node2 and node3 and node1 (inactive) will receive rewards
        final long expectedDebit = -3 * expectedMinNodeReward.getAsLong();
        assertEquals(
                expectedDebit, bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount")));
        // node2 credit
        assertEquals(expectedMinNodeReward.getAsLong(), bodyAdjustments.get(5L));
        // node3 credit
        assertEquals(expectedMinNodeReward.getAsLong(), bodyAdjustments.get(6L));
        // node1 credit
        assertEquals(expectedMinNodeReward.getAsLong(), bodyAdjustments.get(4L));
    }
}
