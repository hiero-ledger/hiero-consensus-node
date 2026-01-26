// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1259;

import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
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
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_COLLECTOR;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.TRANSFERRING_CONTRACT;
import static com.hedera.services.bdd.suites.hip423.ScheduleLongTermSignTest.THIRTY_MINUTES;
import static com.hedera.services.bdd.suites.integration.hip1259.ValidationUtils.*;
import static com.hedera.services.bdd.suites.integration.hip1259.ValidationUtils.feeDistributionValidator;
import static com.hedera.services.bdd.suites.integration.hip1259.ValidationUtils.validateRecordContains;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
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
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
                                feeDistributionValidator(1, List.of(3L, 800L, 801L, 98L), nodeFee::get),
                                1,
                                (spec, item) -> hasFeeDistribution(item, startConsensusTime)),
                        Duration.ofSeconds(1)),
                /*-------------------------------INITIAL SET UP ---------------------------------*/
                cryptoCreate(CIVILIAN_PAYER),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
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
                sourcing(() ->
                        getAccountBalance(FEE_COLLECTOR).hasTinyBars(initialFeeCollectionBalance.get() + txnFee.get())),

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
    @Order(3)
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
                                nodeRewardsWithFeeCollectionValidator(
                                        initialNodeAccountBalance::get, nodeAccountBalanceAfterDistribution::get),
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
                getAccountBalance(NODE_ACCOUNT)
                        .exposingBalanceTo(nodeAccountBalanceAfterDistribution::set)
                        .logged());
    }

    @Order(4)
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
                        .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR));
    }

    /**
     * Verifies that smart contract transfers to the fee collection account (0.0.802) are rejected.
     * Per HIP-1259: "Reject any transaction that would send any hbar to the fee account"
     */
    @Order(5)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> evmTransferToFeeCollectionAccountFails() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(ONE_HBAR).payingWith(CIVILIAN_PAYER),
                // Try to transfer HBAR to fee collection account via smart contract
                contractCall(
                                TRANSFERRING_CONTRACT,
                                "transferToAddress",
                                asHeadlongAddress(asSolidityAddress(0, 0, 802L)),
                                BigInteger.valueOf(1000))
                        .payingWith(CIVILIAN_PAYER)
                        .hasKnownStatus(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED));
    }

    /**
     * Verifies that token associations with the fee collection account (0.0.802) are rejected.
     * Per HIP-1259: "Reject any transaction that would associate a token with the fee account"
     */
    @Order(6)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> tokenAssociationWithFeeCollectionAccountFails() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                tokenCreate("testToken").treasury(CIVILIAN_PAYER),
                tokenAssociate(FEE_COLLECTOR, "testToken")
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(INVALID_ACCOUNT_ID));
    }

    /**
     * Verifies that updates to the fee collection account (0.0.802) are rejected.
     * Per HIP-1259: "Reject any transaction that would update the fee account"
     */
    @Order(7)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> updateFeeCollectionAccountFails() {
        return hapiTest(
                newKeyNamed("newKey"),
                cryptoUpdate(FEE_COLLECTOR)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .key("newKey")
                        .hasKnownStatus(INVALID_ACCOUNT_ID));
    }

    /**
     * Verifies that deletion of the fee collection account (0.0.802) is rejected.
     * Per HIP-1259: "Reject any transaction that would delete the fee account"
     */
    @Order(8)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> deleteFeeCollectionAccountFails() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                cryptoDelete(FEE_COLLECTOR)
                        .transfer(CIVILIAN_PAYER)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(INVALID_ACCOUNT_ID));
    }

    /**
     * Verifies that NFT airdrops to the fee collection account (0.0.802) are rejected.
     * Per HIP-1259: "Reject any transaction that would send any hbar to the fee account"
     * This includes NFT airdrops which are essentially token transfers.
     */
    @Order(10)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> nftAirdropToFeeCollectionAccountFails() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                newKeyNamed("nftSupplyKey"),
                tokenCreate("airdropNft")
                        .treasury(CIVILIAN_PAYER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("nftSupplyKey")
                        .initialSupply(0L),
                mintToken("airdropNft", List.of(ByteString.copyFromUtf8("metadata"))),
                tokenAirdrop(TokenMovement.movingUnique("airdropNft", 1L).between(CIVILIAN_PAYER, FEE_COLLECTOR))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .hasKnownStatus(INVALID_RECEIVING_NODE_ACCOUNT));
    }

    /**
     * Verifies that receiverSigRequired is ignored when distributing fees to node accounts.
     * Per HIP-1259: "receiverSigRequired is ignored for payments to node accounts"
     * This test sets receiverSigRequired=true on a node account and verifies fees are still distributed.
     */
    @Order(11)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> receiverSigRequiredIgnoredForNodeAccountFeePayments() {
        final AtomicLong initialNodeAccountBalance = new AtomicLong(0);
        final AtomicLong nodeAccountBalanceAfterDistribution = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();

        return hapiTest(
                getAccountBalance(NODE_ACCOUNT).exposingBalanceTo(initialNodeAccountBalance::set),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                feeDistributionValidator(1, List.of(3L, 800L, 801L, 98L)),
                                1,
                                (spec, item) -> hasFeeDistribution(item, startConsensusTime)),
                        Duration.ofSeconds(1)),
                cryptoCreate(CIVILIAN_PAYER),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                // Set receiverSigRequired=true on node account 0.0.3
                EmbeddedVerbs.mutateAccount(NODE_ACCOUNT, account -> account.receiverSigRequired(true)),
                // Generate some fees
                fileCreate("testFile")
                        .contents("Test content for fee collection")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                validateRecordContains("feeTxn", FEE_COLLECTOR_ACCOUNT),
                sleepForBlockPeriod(),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Trigger fee distribution at next staking period
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                // Verify node account received fees despite receiverSigRequired=true
                getAccountBalance(NODE_ACCOUNT)
                        .exposingBalanceTo(nodeAccountBalanceAfterDistribution::set)
                        .logged(),
                // Reset receiverSigRequired to false for cleanup
                EmbeddedVerbs.mutateAccount(NODE_ACCOUNT, account -> account.receiverSigRequired(false)));
    }

    /**
     * Verifies that fees are forfeit if a node's account is deleted.
     * Per HIP-1259: "If for any reason the node's account cannot accept the fees
     * (i.e. it is deleted or doesn't exist), then they are forfeit."
     * This test marks a node account as deleted and verifies fees are distributed to 0.0.98, 0.0.800, 0.0.801.
     */
    @Order(12)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> feesAreForfeitWhenNodeAccountIsDeleted() {
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();

        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        // validate node 3 doesnt get any fees
                        selectedItems(
                                feeDistributionValidator(1, List.of(800L, 801L, 98L)),
                                1,
                                (spec, item) -> hasFeeDistribution(item, startConsensusTime)),
                        Duration.ofSeconds(1)),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                // Mark node account 0.0.3 as deleted
                EmbeddedVerbs.mutateAccount(NODE_ACCOUNT, account -> account.deleted(true)),
                // Generate some fees
                fileCreate("testFile")
                        .contents("Test content for fee collection")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                validateRecordContains("feeTxn", FEE_COLLECTOR_ACCOUNT),
                sleepForBlockPeriod(),
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),

                // Trigger fee distribution at next staking period
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                // Verify fee collector still has fees (they were forfeit, not distributed to deleted node account)
                getAccountBalance(FEE_COLLECTOR).hasTinyBars(0L).logged(),
                // Reset node account to not deleted for cleanup
                EmbeddedVerbs.mutateAccount(NODE_ACCOUNT, account -> account.deleted(false)));
    }

    /**
     * Verifies that the NodePayments state is reset (cleared) after fee distribution at staking period boundary.
     * Per HIP-1259: "Reset NodePayments to an empty map" after distribution.
     */
    @Order(13)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nodePaymentsStateResetAfterDistribution() {
        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Generate some fees to populate NodePayments
                fileCreate("testFile")
                        .contents("Test content for fee collection")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                validateRecordContains("feeTxn", FEE_COLLECTOR_ACCOUNT),
                sleepForBlockPeriod(),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),

                // Verify NodePayments has accumulated fees
                EmbeddedVerbs.<NodePayments>viewSingleton(
                        TokenService.NAME,
                        NODE_PAYMENTS_STATE_ID,
                        nodePayments -> assertTrue(
                                !nodePayments.payments().isEmpty(),
                                "NodePayments should have accumulated fees before distribution")),
                // Trigger fee distribution at next staking period
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                cryptoCreate("trigger").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                sleepForBlockPeriod(),
                // Verify NodePayments is reset after distribution
                EmbeddedVerbs.<NodePayments>viewSingleton(
                        TokenService.NAME,
                        NODE_PAYMENTS_STATE_ID,
                        nodePayments -> assertTrue(
                                nodePayments.payments().isEmpty(),
                                "NodePayments should be empty after fee distribution")));
    }

    /**
     * Verifies that fees from various transaction types all go to the fee collection account (0.0.802).
     * Tests crypto, file, token, and contract transactions.
     */
    @Order(14)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> variousTransactionTypesFeesGoToFeeCollector() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                // Crypto transaction
                cryptoCreate("testAccount1")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("cryptoTxn"),
                validateRecordContains("cryptoTxn", FEE_COLLECTOR_ACCOUNT),
                validateRecordNotContains("cryptoTxn", UNEXPECTED_FEE_ACCOUNTS),
                // File transaction
                fileCreate("testFile")
                        .contents("Test content")
                        .payingWith(CIVILIAN_PAYER)
                        .via("fileTxn"),
                validateRecordContains("fileTxn", FEE_COLLECTOR_ACCOUNT),
                validateRecordNotContains("fileTxn", UNEXPECTED_FEE_ACCOUNTS),
                // Token transaction
                tokenCreate("testToken")
                        .treasury(CIVILIAN_PAYER)
                        .payingWith(CIVILIAN_PAYER)
                        .via("tokenTxn"),
                validateRecordContains("tokenTxn", FEE_COLLECTOR_ACCOUNT),
                validateRecordNotContains("tokenTxn", UNEXPECTED_FEE_ACCOUNTS),
                // Contract transaction
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT)
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("contractTxn"),
                validateRecordContains("contractTxn", FEE_COLLECTOR_ACCOUNT),
                validateRecordNotContains("contractTxn", UNEXPECTED_FEE_ACCOUNTS));
    }
    /**
     * Verifies that deleting an account with transfer to the fee collection account (0.0.802) fails.
     * Per HIP-1259: "Reject any transaction that would send any hbar to the fee account"
     */
    @Order(15)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> cryptoDeleteWithTransferToFeeCollectionAccountFails() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate("accountToDelete").balance(ONE_HBAR).payingWith(CIVILIAN_PAYER),
                cryptoCreate("beneficiary").balance(0L).payingWith(CIVILIAN_PAYER),
                // Use explicitDef to set the transfer account ID directly to 0.0.802
                cryptoDelete((spec, b) -> {
                            b.setDeleteAccountID(spec.registry().getAccountID("accountToDelete"));
                            b.setTransferAccountID(
                                    AccountID.newBuilder().setAccountNum(802L).build());
                        })
                        .signedBy(CIVILIAN_PAYER, "accountToDelete")
                        .payingWith(CIVILIAN_PAYER)
                        .hasKnownStatus(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED));
    }

    @Order(15)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> scheduledTransactionFailsWithFeeCollectorCreadit() {
        return hapiTest(
                cryptoCreate("sender").balance(ONE_HBAR),
                cryptoCreate(CIVILIAN_PAYER),
                cryptoCreate("dummy"),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("sender", FEE_COLLECTOR, 1L)))
                        .waitForExpiry(false)
                        .expiringIn(THIRTY_MINUTES)
                        .payingWith(CIVILIAN_PAYER)
                        .via("scheduleCreateTxn"),
                validateRecordContains("scheduleCreateTxn", FEE_COLLECTOR_ACCOUNT),
                scheduleSign("schedule").alsoSigningWith("sender").payingWith(CIVILIAN_PAYER),
                cryptoCreate("trigger").balance(ONE_HBAR).payingWith(CIVILIAN_PAYER),
                getTxnRecord("scheduleCreateTxn")
                        .scheduled()
                        .hasPriority(recordWith().status(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED)));
    }

    /**
     * Verifies that when the network is down for multiple days (e.g., 3 days), only ONE fee distribution
     * is triggered when the network comes back up, not one for each missed day.
     * <p>
     * This tests the behavior documented in the code: the system only checks if we're in a "later" period,
     * not how many periods were skipped. All accumulated fees are distributed in a single transaction.
     */
    @Order(16)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> multiDayOutageOnlyTriggersOneDistribution() {
        final AtomicLong initialFeeCollectionBalance = new AtomicLong(0);
        final AtomicLong feeCollectionBalanceAfterOutage = new AtomicLong(0);
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();

        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                // Validate that exactly ONE fee distribution happens after the multi-day outage
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                feeDistributionValidator(1, List.of(3L, 800L, 801L, 98L)),
                                1, // Expect exactly 1 fee distribution, not 3 (one per day)
                                (spec, item) -> hasFeeDistribution(item, startConsensusTime)),
                        Duration.ofSeconds(1)),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Generate some fees before the "outage"
                fileCreate("testFile1")
                        .contents("Test content before outage")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn1"),
                validateRecordContains("feeTxn1", FEE_COLLECTOR_ACCOUNT),
                sleepForBlockPeriod(),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Record fee collection balance before the "outage"
                getAccountBalance(FEE_COLLECTOR).exposingBalanceTo(initialFeeCollectionBalance::set),
                // Simulate a 3-day network outage by advancing virtual time
                waitUntilStartOfNextStakingPeriod(1),
                waitUntilStartOfNextStakingPeriod(1),
                waitUntilStartOfNextStakingPeriod(1),
                // Network comes back up - first transaction after outage
                // This should trigger exactly ONE fee distribution, not three
                cryptoCreate("afterOutage").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                // Verify fee collection account was drained (fees were distributed)
                getAccountBalance(FEE_COLLECTOR)
                        .exposingBalanceTo(feeCollectionBalanceAfterOutage::set)
                        .logged(),
                doingContextual(spec -> {
                    // Fee collection account should have been drained by the distribution
                    assertTrue(
                            feeCollectionBalanceAfterOutage.get() < initialFeeCollectionBalance.get(),
                            "Fee collection account should have been drained after distribution. "
                                    + "Before: " + initialFeeCollectionBalance.get()
                                    + ", After: " + feeCollectionBalanceAfterOutage.get());
                }));
    }

    /**
     * Verifies that fees accumulate correctly across multiple transactions within the same staking period.
     * Per HIP-1259: "Add the node fee to the corresponding entry in the NodePayments map"
     */
    @Order(17)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> feesAccumulateAcrossMultipleTransactions() {
        final AtomicLong initialFeeCollectionBalance = new AtomicLong(0);
        final AtomicLong balanceAfterTransactions = new AtomicLong(0);
        final AtomicLong firstTxnFee = new AtomicLong(0);
        final AtomicLong secondTxnFee = new AtomicLong(0);
        final AtomicLong thirdTxnFee = new AtomicLong(0);

        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                getAccountBalance(FEE_COLLECTOR).exposingBalanceTo(initialFeeCollectionBalance::set),
                // Execute multiple transactions and capture their fees
                cryptoCreate("account1")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("txn1"),
                getTxnRecord("txn1").exposingTo(record -> firstTxnFee.set(record.getTransactionFee())),
                cryptoCreate("account2")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("txn2"),
                getTxnRecord("txn2").exposingTo(record -> secondTxnFee.set(record.getTransactionFee())),
                cryptoCreate("account3")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("txn3"),
                getTxnRecord("txn3").exposingTo(record -> thirdTxnFee.set(record.getTransactionFee())),
                // Verify fee collection account balance increased by approximately the sum of fees
                getAccountBalance(FEE_COLLECTOR).exposingBalanceTo(balanceAfterTransactions::set),
                doingContextual(spec -> {
                    final long totalFees = firstTxnFee.get() + secondTxnFee.get() + thirdTxnFee.get();
                    final long balanceIncrease = balanceAfterTransactions.get() - initialFeeCollectionBalance.get();
                    assertTrue(
                            balanceIncrease >= totalFees,
                            "Fee collection account balance should increase by at least the sum of transaction fees. "
                                    + "Expected at least " + totalFees + " but got " + balanceIncrease);
                }));
    }
}
