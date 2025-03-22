// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
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

import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(4)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableHip1064Tests {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.nodeRewardsEnabled", "true",
                "ledger.numSystemAccounts", "3"));
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
     *     <li>Neither {@code node0} and {@code node1} receive any rewards.</li>
     * </ol>
     */
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> paysAdjustedFeesToAllEligibleActiveAccountsAtStartOfNewPeriod() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        final AtomicLong expectedNodeRewards = new AtomicLong(0);
        return hapiTest(
                recordStreamMustIncludePassFrom(
                        selectedItems(
                                nodeRewardsValidator(expectedNodeRewards::get),
                                // We expect one node rewards payment in this test
                                1,
                                (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                        .anyMatch(aa ->
                                                aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L)),
                        Duration.ofSeconds(1)),
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
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
                doWithStartupConfig(
                        "nodes.targetUsdNodeRewards",
                        target -> doWithStartupConfig(
                                "nodes.numPeriodsToTargetUsd",
                                numPeriods -> doingContextual(spec -> {
                                    final long targetReward = (Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE)
                                            / Integer.parseInt(numPeriods);
                                    final long targetTinybars =
                                            spec.ratesProvider().toTbWithActiveRates(targetReward);
                                    final long prePaidRewards = expectedNodeFees.get() / 4;
                                    expectedNodeRewards.set(targetTinybars - prePaidRewards);
                                    System.out.println("Expected node rewards: " + expectedNodeRewards.get());
                                }))),
                // Start a new period and leave only node1 as inactive
                mutateSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(2, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(4, nodeRewards.nodeActivities().size());
                    assertEquals(expectedNodeFees.get(), nodeRewards.feesCollectedByRewardEligibleNodes());
                    return nodeRewards
                            .copyBuilder()
                            .nodeActivities(NodeActivity.newBuilder()
                                    .nodeId(1)
                                    .numMissedJudgeRounds(2)
                                    .build())
                            .build();
                }),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS));
    }

    private static VisibleItemsValidator nodeRewardsValidator(@NonNull final LongSupplier expectedPerNodeReward) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            System.out.println("BODY: " + op);
            final long expectedPerNode = expectedPerNodeReward.getAsLong();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            assertEquals(3, bodyAdjustments.size());
            // node2 and node3 only expected to receive (node0 is system, node1 was inactive)
            final long expectedDebit = -2 * expectedPerNode;
            assertEquals(
                    expectedDebit, bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount")));
            // node2 credit
            assertEquals(expectedPerNode, bodyAdjustments.get(5L));
            // node3 credit
            assertEquals(expectedPerNode, bodyAdjustments.get(6L));
            System.out.println(op.getTransfers());
            System.out.println(payment.transactionRecord());
        };
    }
}
