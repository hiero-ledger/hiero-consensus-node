// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that an atomic batch cannot carry a node update. The committed state has node 2 declining node rewards; a
 * batch that tries to re-enable rewards for node 2 is rejected with {@code BATCH_TRANSACTION_IN_BLACKLIST}. At the
 * next node-reward period boundary, the payment follows committed state and skips node 2's account (0.0.5).
 * Node-reward distribution is deterministic under repeatable virtual time, so the assertion is stable.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class AtomicBatchNodeUpdateBlacklistTest {

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String BROKE = "broke";
    private static final long NODE2_ACCOUNT_NUM = 5L;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.nodeRewardsEnabled", "true",
                "nodes.preserveMinNodeRewardBalance", "true",
                "ledger.transfers.maxLen", "2",
                "nodes.feeCollectionAccountEnabled", "false"));
        // Start every node accepting rewards; the test then makes node 2 decline in committed state.
        testLifecycle.doAdhoc(
                nodeUpdate("0").declineReward(false),
                nodeUpdate("1").declineReward(false),
                nodeUpdate("2").declineReward(false),
                nodeUpdate("3").declineReward(false));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> nodeRewardOptInInBatchIsRejected() {
        final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(100 * ONE_HBAR),
                cryptoCreate(BROKE).balance(0L),
                doingContextual(spec -> startConsensusTime.set(spec.consensusTime())),
                // The node-reward payment at the next period boundary must not credit node 2's account.
                streamMustIncludePassWithoutBackgroundTrafficFrom(
                        selectedItems(
                                node2IsNotRewarded(),
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                                .anyMatch(
                                                        aa -> aa.getAccountID().getAccountNum() == 801L
                                                                && aa.getAmount() < 0L)
                                        && asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                                                .isAfter(startConsensusTime.get())),
                        Duration.ofSeconds(1)),
                cryptoTransfer(TokenMovement.movingHbar(100_000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Committed state: node 2 declines rewards.
                nodeUpdate("2").declineReward(true),
                // A batch that tries to re-enable rewards for node 2 is rejected.
                atomicBatch(
                                nodeUpdate("2")
                                        .declineReward(false)
                                        .signedBy(GENESIS)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(tinyBarsFromTo(BROKE, NODE_REWARD, ONE_HBAR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR, BROKE)
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                sleepForBlockPeriod(),
                cryptoCreate("forceBlockBoundary").payingWith(GENESIS),
                // Keep node 1 inactive so the payout set is small and deterministic; nodes 2 and 3 stay active, so
                // node 3 is paid (proving a payment happens) while node 2 must be skipped for declining in state.
                mutateSingleton(TokenService.NAME, NODE_REWARDS_STATE_ID, (NodeRewards nodeRewards) -> nodeRewards
                        .copyBuilder()
                        .nodeActivities(NodeActivity.newBuilder()
                                .nodeId(1)
                                .numMissedJudgeRounds(nodeRewards.numRoundsInStakingPeriod())
                                .build())
                        .build()),
                getAccountBalance(NODE_REWARD).logged(),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoCreate("nobody").payingWith(GENESIS),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    private static VisibleItemsValidator node2IsNotRewarded() {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No node reward payment was found");
            assertTrue(items.size() >= 1, "Expected at least one node reward payment");
            final var payment = items.getFirst();
            assertTrue(payment.function() == CryptoTransfer, "Reward payment should be a CryptoTransfer");
            final Set<Long> credited =
                    payment.body().getCryptoTransfer().getTransfers().getAccountAmountsList().stream()
                            .filter(aa -> aa.getAmount() > 0L)
                            .map(aa -> aa.getAccountID().getAccountNum())
                            .collect(Collectors.toSet());
            assertFalse(
                    credited.contains(NODE2_ACCOUNT_NUM),
                    "Node 2 (0.0." + NODE2_ACCOUNT_NUM + ") must not receive a node reward: its committed "
                            + "declineReward is true. Credited: " + credited);
        };
    }
}
