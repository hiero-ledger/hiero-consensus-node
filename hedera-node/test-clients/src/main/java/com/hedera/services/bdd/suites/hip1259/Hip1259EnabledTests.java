// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1259;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
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
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_COLLECTOR;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip1259.Hip1259DisabledTests.validateRecordContains;
import static com.hedera.services.bdd.suites.hip1259.Hip1259DisabledTests.validateRecordNotContains;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.TokenService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * Tests for HIP-1259 Fee Collection Account when the feature is enabled.
 * These tests verify:
 * 1. All fees go to the fee collection account (0.0.802) instead of being distributed immediately
 * 2. Fees are accumulated in the NodePayments state during the staking period
 * 3. At staking period boundary, fees are distributed from 0.0.802 to node accounts and system accounts
 * 4. Node rewards interact correctly with the fee collection mechanism
 */
@Order(8)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Hip1259EnabledTests {

    private static final List<Long> EXPECTED_FEE_ACCOUNTS = List.of(802L);
    private static final List<Long> UNEXPECTED_FEE_ACCOUNTS = List.of(3L, 98L, 800L, 801L);

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.feeCollectionAccountEnabled", "true",
                "nodes.nodeRewardsEnabled", "true",
                "nodes.preserveMinNodeRewardBalance", "true"));
        testLifecycle.doAdhoc(
                nodeUpdate("0").declineReward(false),
                nodeUpdate("1").declineReward(false),
                nodeUpdate("2").declineReward(false),
                nodeUpdate("3").declineReward(false));
    }

    /**
     * Verifies that when HIP-1259 is enabled, all transaction fees go to the fee collection
     * account (0.0.802) instead of being distributed to individual node and system accounts.
     */
    @Order(1)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> feesGoToFeeCollectionAccount() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("testFile")
                        .contents("Test content for fee collection")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeCollectionTxn"),
                getTxnRecord("feeCollectionTxn").logged(),
                validateRecordContains("feeCollectionTxn", EXPECTED_FEE_ACCOUNTS),
                validateRecordNotContains("feeCollectionTxn", UNEXPECTED_FEE_ACCOUNTS));
    }

    /**
     * Verifies that fees are accumulated in the NodeRewards state during the staking period
     * and the fee collection account balance increases accordingly.
     */
    @Order(2)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> feesAccumulateInNodeRewardsState() {
        final AtomicLong initialFeeCollectionBalance = new AtomicLong(0);
        final AtomicLong initialNodeFeesCollected = new AtomicLong(0);
        final AtomicLong txnFee = new AtomicLong(0);
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER),
                waitUntilStartOfNextStakingPeriod(1),
                getAccountBalance(FEE_COLLECTOR).exposingBalanceTo(initialFeeCollectionBalance::set),
                sleepForBlockPeriod(),
                // initial node fees before transaction
                EmbeddedVerbs.<NodeRewards>viewSingleton(
                        TokenService.NAME,
                        NODE_REWARDS_STATE_ID,
                        nodeRewards -> initialNodeFeesCollected.set(nodeRewards.nodeFeesCollected())),
                cryptoCreate("testAccount")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                getTxnRecord("feeTxn")
                        .exposingTo(r -> txnFee.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(aa -> aa.getAccountID().getAccountNum() == 802L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                // Total fee charged shouldn't change
                validateChargedUsd("feeTxn", 0.05, 1),
                sleepForBlockPeriod(),
                EmbeddedVerbs.<NodeRewards>viewSingleton(
                        TokenService.NAME,
                        NODE_REWARDS_STATE_ID,
                        nodeRewards -> assertTrue(
                                nodeRewards.nodeFeesCollected() > initialNodeFeesCollected.get(),
                                "Node fees collected should increase after transaction")),
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(spec -> actual -> {
                    if (actual > initialFeeCollectionBalance.get()) {
                        return Optional.empty();
                    }
                    return Optional.of("Fee collection balance should increase after transaction");
                })
        );
    }

    /**
     * Verifies that at the staking period boundary, fees accumulated in the fee collection account
     * are distributed to node accounts via a synthetic transaction.
     */
    @Order(3)
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {"nodes.minPerPeriodNodeRewardUsd"})
    final Stream<DynamicTest> feesDistributedAtStakingPeriodBoundary() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                feeDistributionValidator(expectedNodeFees::get),
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                                .anyMatch(
                                                        aa -> aa.getAccountID().getAccountNum() == 802L
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
                validateRecordContains("notFree", EXPECTED_FEE_ACCOUNTS),
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 802L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                mutateSingleton(TokenService.NAME, NODE_REWARDS_STATE_ID, (NodeRewards nodeRewards) -> nodeRewards
                        .copyBuilder()
                        .nodeActivities(NodeActivity.newBuilder()
                                .nodeId(1)
                                .numMissedJudgeRounds(3)
                                .build())
                        .build()),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Verifies that node rewards are correctly calculated and distributed when HIP-1259 is enabled,
     * taking into account the fees collected in the fee collection account.
     */
    @Order(4)
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {"nodes.minPerPeriodNodeRewardUsd"})
    final Stream<DynamicTest> nodeRewardsWithFeeCollectionEnabled() {
        final AtomicLong nodeRewardBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsWithFeeCollectionValidator(nodeRewardBalance::get),
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
                validateRecordContains("notFree", EXPECTED_FEE_ACCOUNTS),
                sleepForBlockPeriod(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                mutateSingleton(TokenService.NAME, NODE_REWARDS_STATE_ID, (NodeRewards nodeRewards) -> nodeRewards
                        .copyBuilder()
                        .nodeActivities(NodeActivity.newBuilder()
                                .nodeId(1)
                                .numMissedJudgeRounds(3)
                                .build())
                        .build()),
                getAccountBalance(NODE_REWARD)
                        .exposingBalanceTo(nodeRewardBalance::set)
                        .logged(),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Verifies that the fee collection account balance is reset after fees are distributed
     * at the staking period boundary.
     */
    @Order(5)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> feeCollectionAccountResetAfterDistribution() {
        final AtomicLong preDistributionBalance = new AtomicLong(0);
        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("testFile")
                        .contents("Test content")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                validateRecordContains("feeTxn", EXPECTED_FEE_ACCOUNTS),
                sleepForBlockPeriod(),
                getAccountBalance(FEE_COLLECTOR)
                        .exposingBalanceTo(preDistributionBalance::set)
                        .logged(),
                EmbeddedVerbs.handleAnyRepeatableQueryPayment(),
                mutateSingleton(TokenService.NAME, NODE_REWARDS_STATE_ID, (NodeRewards nodeRewards) -> nodeRewards
                        .copyBuilder()
                        .nodeActivities(List.of(
                                NodeActivity.newBuilder()
                                        .nodeId(0)
                                        .numMissedJudgeRounds(0)
                                        .build(),
                                NodeActivity.newBuilder()
                                        .nodeId(1)
                                        .numMissedJudgeRounds(0)
                                        .build(),
                                NodeActivity.newBuilder()
                                        .nodeId(2)
                                        .numMissedJudgeRounds(0)
                                        .build(),
                                NodeActivity.newBuilder()
                                        .nodeId(3)
                                        .numMissedJudgeRounds(0)
                                        .build()))
                        .build()),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("trigger").payingWith(GENESIS),
                sleepForBlockPeriod(),
                EmbeddedVerbs.<NodeRewards>viewSingleton(
                        TokenService.NAME,
                        NODE_REWARDS_STATE_ID,
                        nodeRewards -> assertEquals(
                                0L, nodeRewards.nodeFeesCollected(), "Node fees should be reset after distribution")));
    }

    /**
     * Validator for fee distribution synthetic transaction.
     */
    static VisibleItemsValidator feeDistributionValidator(@NonNull final LongSupplier expectedFees) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No fee distribution found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            // Fee collection account should be debited
            assertTrue(
                    bodyAdjustments.containsKey(802L) && bodyAdjustments.get(802L) < 0,
                    "Fee collection account should be debited");
        };
    }

    /**
     * Validator for node rewards with fee collection enabled.
     */
    static VisibleItemsValidator nodeRewardsWithFeeCollectionValidator(@NonNull final LongSupplier nodeRewardBalance) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            // Node reward account should be debited
            final long nodeRewardDebit =
                    bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount"));
            assertTrue(nodeRewardDebit < 0, "Node reward account should be debited");
        };
    }
}
