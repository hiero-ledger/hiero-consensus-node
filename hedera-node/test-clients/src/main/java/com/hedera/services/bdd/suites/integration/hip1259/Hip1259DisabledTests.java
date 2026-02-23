// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1259;

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
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_COLLECTOR;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.integration.hip1259.ValidationUtils.validateRecordContains;
import static com.hedera.services.bdd.suites.integration.hip1259.ValidationUtils.validateRecordNotContains;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Tests for HIP-1259 Fee Collection Account when the feature is disabled.
 * These tests verify:
 * 1. Fees are distributed immediately to node accounts (0.0.3) and system accounts (0.0.801, 0.0.98, 0.0.800)
 * 2. The fee collection account (0.0.802) is NOT used
 * 3. Node rewards work correctly with the legacy fee distribution mechanism
 */
@Order(19)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Hip1259DisabledTests {

    private static final List<Long> LEGACY_FEE_ACCOUNTS = List.of(3L, 801L);
    private static final List<Long> FEE_COLLECTION_ACCOUNT = List.of(802L);

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.feeCollectionAccountEnabled", "false",
                "nodes.nodeRewardsEnabled", "true",
                "nodes.preserveMinNodeRewardBalance", "true"));
    }

    /**
     * Verifies that when HIP-1259 is disabled, transaction fees go to the node account (0.0.3)
     * and system accounts (0.0.801) instead of the fee collection account (0.0.802).
     */
    @Order(1)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> feesGoToLegacyAccounts() {
        final AtomicLong initialFeeCollectionBalance = new AtomicLong(0);
        return hapiTest(
                getAccountBalance(FEE_COLLECTOR).exposingBalanceTo(initialFeeCollectionBalance::set),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate("testAccount").payingWith(CIVILIAN_PAYER).via("feeTxn"),
                getTxnRecord("feeTxn").logged(),
                validateRecordContains("feeTxn", LEGACY_FEE_ACCOUNTS),
                validateRecordNotContains("feeTxn", FEE_COLLECTION_ACCOUNT),
                getAccountBalance(FEE_COLLECTOR)
                        .hasTinyBars(spec -> actual -> {
                            if (actual == initialFeeCollectionBalance.get()) {
                                return Optional.empty();
                            }
                            return Optional.of("Fee collection balance should not change when HIP-1259 is disabled");
                        })
                        .logged());
    }

    /**
     * Verifies that node fees are tracked in NodeRewards state even when HIP-1259 is disabled,
     * for the purpose of calculating node rewards.
     */
    @Order(3)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nodeFeesTrackedForRewardsCalculation() {
        final AtomicLong initialNodeFeesCollected = new AtomicLong(0);
        return hapiTest(
                waitUntilStartOfNextStakingPeriod(1),
                sleepForBlockPeriod(),
                EmbeddedVerbs.<NodeRewards>viewSingleton(
                        TokenService.NAME,
                        NODE_REWARDS_STATE_ID,
                        nodeRewards -> initialNodeFeesCollected.set(nodeRewards.nodeFeesCollected())),
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("testFile")
                        .contents("Test content")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                validateRecordContains("feeTxn", LEGACY_FEE_ACCOUNTS),
                sleepForBlockPeriod(),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(GENESIS, NODE_REWARD)),
                EmbeddedVerbs.<NodeRewards>viewSingleton(
                        TokenService.NAME,
                        NODE_REWARDS_STATE_ID,
                        nodeRewards -> assertTrue(
                                nodeRewards.nodeFeesCollected() > initialNodeFeesCollected.get(),
                                "Node fees should be tracked for rewards calculation even when HIP-1259 is disabled")));
    }

    /**
     * Verifies that at staking period boundary, node fees are reset even when HIP-1259 is disabled.
     */
    @Order(6)
    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nodeFeesResetAtStakingPeriodBoundary() {
        return hapiTest(
                cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("testFile")
                        .contents("Test content")
                        .payingWith(CIVILIAN_PAYER)
                        .via("feeTxn"),
                validateRecordContains("feeTxn", LEGACY_FEE_ACCOUNTS),
                sleepForBlockPeriod(),
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
                                0L,
                                nodeRewards.nodeFeesCollected(),
                                "Node fees should be reset after staking period")));
    }

    /**
     * Verifies that various transaction types all distribute fees to legacy accounts
     * (node account and 0.0.801) when HIP-1259 is disabled.
     */
    @Order(7)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> variousTransactionTypesFeesGoToLegacyAccounts() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                // Crypto transaction
                cryptoCreate("testAccount1")
                        .balance(ONE_HBAR)
                        .payingWith(CIVILIAN_PAYER)
                        .via("cryptoTxn"),
                validateRecordContains("cryptoTxn", LEGACY_FEE_ACCOUNTS),
                validateRecordNotContains("cryptoTxn", FEE_COLLECTION_ACCOUNT),
                // File transaction
                fileCreate("testFile")
                        .contents("Test content")
                        .payingWith(CIVILIAN_PAYER)
                        .via("fileTxn"),
                validateRecordContains("fileTxn", LEGACY_FEE_ACCOUNTS),
                validateRecordNotContains("fileTxn", FEE_COLLECTION_ACCOUNT));
    }

    /**
     * Verifies that transfers to the fee collection account (0.0.802) are still rejected
     * even when HIP-1259 is disabled. The account should still be protected.
     */
    @Order(8)
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> transferToFeeCollectionAccountStillRejectedWhenDisabled() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, FEE_COLLECTOR))
                        .hasKnownStatus(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED));
    }
}
