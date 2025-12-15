// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1259;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_COLLECTOR;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.integration.RepeatableStreamValidators.validateRecordContains;
import static com.hedera.services.bdd.suites.integration.RepeatableStreamValidators.validateRecordNotContains;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodePayment;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
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

/**
 * Tests for HIP-1259 Fee Collection Account when the feature is enabled.
 * These tests verify:
 * 1. All fees go to the fee collection account (0.0.802) instead of being distributed immediately
 * 2. Fees are accumulated in the NodePayments state during the staking period
 * 3. At staking period boundary, fees are distributed from 0.0.802 to node accounts and system accounts
 * 4. Node rewards interact correctly with the fee collection mechanism
 */
@Order(20)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@OrderedInIsolation
public class Hip1259EnabledTests {
    private static final List<Long> FEE_COLLECTOR_ACCOUNT = List.of(802L);
    private static final List<Long> UNEXPECTED_FEE_ACCOUNTS = List.of(3L, 98L, 800L, 801L);
    private static final String NODE_ACCOUNT = "0.0.3";

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
        cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD));
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
                validateRecordContains("feeCollectionTxn", FEE_COLLECTOR_ACCOUNT),
                validateRecordNotContains("feeCollectionTxn", UNEXPECTED_FEE_ACCOUNTS));
    }

    /**
     * Verifies that fees are accumulated in the NodeRewards state during the staking period
     * and the fee collection account balance increases accordingly.
     */
    @Order(2)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> feesAccumulateInNodePaymentsStateAndDistributedAtStakingPeriodBoundary() {
        final AtomicLong initialFeeCollectionBalance = new AtomicLong(0);
        final AtomicLong initialNodeFeesCollected = new AtomicLong(0);
        final AtomicLong txnFee = new AtomicLong(0);
        final AtomicLong nodeFee = new AtomicLong(0);
        final AtomicLong nodeAccountBalance = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();


        return hapiTest(
                getAccountBalance(NODE_ACCOUNT).exposingBalanceTo(nodeAccountBalance::set),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                feeDistributionValidator(1, List.of(3L, 800L, 801L, 98L), nodeFee::get, nodeAccountBalance::get),
                                1,
                                (spec, item) -> hasFeeDistribution(item, startConsensusTime)),
                        Duration.ofSeconds(1)),
                /*-------------------------------INITIAL SET UP ---------------------------------*/
                cryptoCreate(CIVILIAN_PAYER),
                waitUntilStartOfNextStakingPeriod(1),
                mutateSingleton(TokenService.NAME, NODE_PAYMENTS_STATE_ID, (NodePayments nodePayments) -> nodePayments
                        .copyBuilder()
                        .payments(List.of())
                        .build()),

                /*-------------------------------TRIGGER NEXT STAKING PERIOD ---------------------------------*/
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD))
                        .via("distributionTrigger"),
                // record fee collector account before the transaction of interest
                getAccountBalance(FEE_COLLECTOR).exposingBalanceTo(initialFeeCollectionBalance::set),

                cryptoCreate("testAccount")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                // verify fee collection account balance increased by transaction fee
                getTxnRecord("feeTxn").exposingTo(record -> txnFee.set(record.getTransactionFee())),
                sourcing(() -> getAccountBalance(FEE_COLLECTOR)
                        .hasTinyBars(initialFeeCollectionBalance.get() + txnFee.get())),

                // Node fees should increase after transaction
                sleepForBlockPeriod(),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                EmbeddedVerbs.<NodePayments>viewSingleton(TokenService.NAME, NODE_PAYMENTS_STATE_ID, nodePayments -> {
                    final var newPayments = nodePayments.payments().stream()
                            .mapToLong(NodePayment::fees)
                            .sum();
                    nodeFee.set(newPayments - initialNodeFeesCollected.get());
                    assertTrue(
                            newPayments > initialNodeFeesCollected.get(),
                            "Node fees collected should increase after transaction");
                }),


                validateRecordContains("feeTxn", FEE_COLLECTOR_ACCOUNT),
                validateRecordNotContains("feeTxn", UNEXPECTED_FEE_ACCOUNTS),

                // fee charged for transaction should never change
                validateChargedUsd("feeTxn", 0.05, 1),

                /*-------------------------------TRIGGER NEXT STAKING PERIOD ---------------------------------*/
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Verifies that node rewards are correctly calculated and distributed when HIP-1259 is enabled,
     * taking into account the fees collected in the fee collection account.
     */
    @Order(5)
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {"nodes.minPerPeriodNodeRewardUsd"})
    final Stream<DynamicTest> nodeRewardsDistributedAfterFeeDistribution() {
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        final AtomicLong initialNodeAccountBalance = new AtomicLong(0);
        final AtomicLong nodeAccountBalanceAfterDistribution = new AtomicLong(0);
        return hapiTest(
                getAccountBalance(NODE_ACCOUNT).exposingBalanceTo(initialNodeAccountBalance::set),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                nodeRewardsWithFeeCollectionValidator(initialNodeAccountBalance::get, nodeAccountBalanceAfterDistribution::get),
                                2,
                                (spec, item) -> isNodeRewardOrFeeDistribution(item, startConsensusTime)),
                        Duration.ofSeconds(1)),

                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                nodeUpdate("0").declineReward(true),
                waitUntilStartOfNextStakingPeriod(1),
                /* --------------------- NEW STAKING PERIOD --------------------- */
                cryptoCreate(CIVILIAN_PAYER),
                cryptoCreate("testAccount")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                validateRecordContains("feeTxn", FEE_COLLECTOR_ACCOUNT),
                sleepForBlockPeriod(),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Set nodes to have perfect activity - no missed rounds
                mutateSingleton(TokenService.NAME, NODE_REWARDS_STATE_ID, (NodeRewards nodeRewards) -> nodeRewards
                        .copyBuilder()
                        .nodeActivities(
                                NodeActivity.newBuilder()
                                        .nodeId(1)
                                        .numMissedJudgeRounds(0)
                                        .build(), // 100% active
                                NodeActivity.newBuilder()
                                        .nodeId(2)
                                        .numMissedJudgeRounds(0)
                                        .build(), // 100% active
                                NodeActivity.newBuilder()
                                        .nodeId(3)
                                        .numMissedJudgeRounds(0)
                                        .build()) // 100% active
                        .build()),
                getAccountBalance(NODE_ACCOUNT).logged(),
                /* --------------------- NEW STAKING PERIOD --------------------- */
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                getAccountBalance(FEE_COLLECTOR).logged(),
                getAccountBalance(NODE_ACCOUNT).exposingBalanceTo(nodeAccountBalanceAfterDistribution::set).logged());
    }

    @Order(10)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> transferToFeeCollectionAccountFails() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, FEE_COLLECTOR))
                        .hasKnownStatus(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED),
                // token transfers or NFT transfers also not allowed
                newKeyNamed("supplyKey"),
                tokenCreate("token")
                        .treasury(CIVILIAN_PAYER)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(ONE_HBAR, "token").between(CIVILIAN_PAYER, FEE_COLLECTOR))
                        .hasKnownStatus(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED),
                // Do NFT transfer
                tokenCreate("nft")
                        .treasury(CIVILIAN_PAYER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .initialSupply(0L),
                mintToken("nft", List.of(ByteString.copyFromUtf8("meta"))),
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between(CIVILIAN_PAYER, FEE_COLLECTOR))
                        .hasKnownStatus(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED),
                // adding as fee collector for custom fees also not allowed
                tokenCreate("tokenWithFee")
                        .treasury(CIVILIAN_PAYER)
                        .withCustom(fixedHbarFee(1, FEE_COLLECTOR))
                        .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                // TODO: Add a case tp transfer via EVM
        );
    }


    private static boolean isNodeRewardOrFeeDistribution(final RecordStreamItem item,
                                                         final AtomicReference<Instant> startConsensusTime) {
        return item.getRecord().getTransferList().getAccountAmountsList().stream()
                .anyMatch(aa -> {
                    final var accountNum = aa.getAccountID().getAccountNum();
                    final var amount = aa.getAmount();
                    final var isAfter = asInstant(toPbj(item.getRecord().getConsensusTimestamp())).minusSeconds(60).isAfter(startConsensusTime.get());
                    return ((accountNum == 801L || accountNum == 802L) && amount < 0L) && isAfter;
                });
    }

    private static boolean hasFeeDistribution(final RecordStreamItem item,
                                              final AtomicReference<Instant> startConsensusTime) {
        return item.getRecord().getTransferList().getAccountAmountsList().stream()
                .anyMatch(aa -> {
                    final var accountNum = aa.getAccountID().getAccountNum();
                    final var amount = aa.getAmount();
                    final var isAfter = asInstant(toPbj(item.getRecord().getConsensusTimestamp())).minusSeconds(60).isAfter(startConsensusTime.get());
                    return ((accountNum == 802L) && amount < 0L) && isAfter;
                });
    }

    /**
     * Validator for fee distribution synthetic transaction.
     */
    static VisibleItemsValidator feeDistributionValidator(
            int recordNumber,
            List<Long> creditAccounts,
            @NonNull final LongSupplier expectedFees,
            @NonNull final LongSupplier initialNodeBalance) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No fee distribution found");
            assertNotNull(items.get(recordNumber - 1), "No fee distribution found");
            final var payment = items.get(recordNumber - 1);
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            for (Long account : creditAccounts) {
                assertTrue(
                        bodyAdjustments.containsKey(account) && bodyAdjustments.get(account) > 0,
                        "Credit account should be credited");
            }
            assertEquals((long) bodyAdjustments.get(3L), expectedFees.getAsLong(), "Node account fee should match");
            getAccountBalance(NODE_ACCOUNT).hasTinyBars(initialNodeBalance.getAsLong() + expectedFees.getAsLong());
        };
    }

    /**
     * Validator for node rewards with fee collection enabled.
     * Validates that:
     * 1. Fee distributions happen first (0.0.802 is debited, node accounts and system accounts are credited)
     * 2. Node rewards are distributed last (0.0.801 is debited, node accounts are credited)
     */
    static VisibleItemsValidator nodeRewardsWithFeeCollectionValidator(
                                                                       @NonNull final LongSupplier initialNodeBalance,
                                                                       @NonNull final LongSupplier nodeAccountBalanceAfterDistribution) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments or fee distributions found");
            assertTrue(items.size() >= 2, "Expected at least 2 records (fee distribution + node rewards)");

            // Always fee distributions happen first and then node rewards
            // validate the order of the transactions
            boolean foundFeeDistribution = false;
            boolean foundNodeReward = false;
            int feeDistributionIndex = -1;
            int nodeRewardIndex = -1;
            long nodeFees = 0L;

            for (int i = 0; i < items.size(); i++) {
                final var payment = items.get(i);
                assertEquals(CryptoTransfer, payment.function());
                final var op = payment.body().getCryptoTransfer();
                final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                        .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));

                // Check if this is a fee distribution (0.0.802 is debited)
                if (bodyAdjustments.containsKey(802L) && bodyAdjustments.get(802L) < 0) {
                    foundFeeDistribution = true;
                    if (feeDistributionIndex == -1) {
                        feeDistributionIndex = i;
                    }
                    // Validate fee distribution: 0.0.802 debited, node accounts (3) and system accounts credited
                    assertTrue(
                            bodyAdjustments.containsKey(3L) && bodyAdjustments.get(3L) > 0,
                            "Node account 0.0.3 should be credited in fee distribution");
                    assertTrue(
                            bodyAdjustments.containsKey(802L) && bodyAdjustments.get(802L) < 0,
                            "System account 0.0.802 should be debited in fee distribution");
                    if (bodyAdjustments.containsKey(3L)) {
                        nodeFees += bodyAdjustments.get(3L);
                    }
                }

                // Check if this is a node reward distribution (0.0.801 is debited)
                if (bodyAdjustments.containsKey(801L) && bodyAdjustments.get(801L) < 0) {
                    foundNodeReward = true;
                    nodeRewardIndex = i;
                    // Validate node reward: 0.0.801 debited, node accounts credited
                    final long nodeRewardDebit = bodyAdjustments.get(801L);
                    assertTrue(nodeRewardDebit < 0, "Node reward account 0.0.801 should be debited");

                    // Sum of credits to node accounts should equal the debit from 0.0.801
                    final long totalCredits = bodyAdjustments.entrySet().stream()
                            .filter(e -> e.getKey() != 801L && e.getValue() > 0)
                            .mapToLong(Map.Entry::getValue)
                            .sum();
                    assertEquals(
                            -nodeRewardDebit,
                            totalCredits,
                            "Total credits to node accounts should equal debit from 0.0.801");
                }
            }

            assertTrue(foundFeeDistribution, "Should have at least one fee distribution transaction");
            assertTrue(foundNodeReward, "Should have at least one node reward transaction");
            assertTrue(feeDistributionIndex < nodeRewardIndex, "Fee distribution should happen before node rewards");
            assertEquals( initialNodeBalance.getAsLong() + nodeFees, nodeAccountBalanceAfterDistribution.getAsLong(),"Node account balance should match");
        };
    }
}
