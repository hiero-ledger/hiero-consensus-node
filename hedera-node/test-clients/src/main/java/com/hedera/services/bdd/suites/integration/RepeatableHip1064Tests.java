// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                viewSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    System.out.println("BEFORE: " + nodeRewards);
                }),
                viewSingleton("TokenService", "STAKING_NETWORK_REWARDS", (stakingRewards) -> {
                    System.out.println("WTF: " + stakingRewards);
                }),
                cryptoCreate(CIVILIAN_PAYER),
                viewSingleton("TokenService", "STAKING_NETWORK_REWARDS", (stakingRewards) -> {
                    System.out.println("WTF2: " + stakingRewards);
                }),
                viewSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    System.out.println("AFTER : " + nodeRewards);
                }),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                viewSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    System.out.println("AGAIN : " + nodeRewards);
                }),
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
                                    final long targetTinybars = spec.ratesProvider()
                                            .toTbWithActiveRates(Long.parseLong(target) * 100 * TINY_PARTS_PER_WHOLE);
                                    final long targetReward = targetTinybars / Integer.parseInt(numPeriods);
                                    final long prePaidRewards = expectedNodeFees.get() / 4;
                                    expectedNodeRewards.set(targetReward - prePaidRewards);
                                }))),
                // Start a new period
                viewSingleton("TokenService", "NODE_REWARDS", (NodeRewards nodeRewards) -> {
                    assertEquals(2, nodeRewards.numRoundsInStakingPeriod());
                    assertEquals(expectedNodeFees.get(), nodeRewards.feesCollectedByRewardEligibleNodes());
                }),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                cryptoCreate("nobody").payingWith(GENESIS));
    }
}
