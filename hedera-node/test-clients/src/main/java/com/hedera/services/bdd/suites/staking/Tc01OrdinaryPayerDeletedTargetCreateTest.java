// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Verifies that CryptoCreate rejects a deleted account as stakedAccountId, preventing the
 * under-reserved staking reward payout described in TC-01.
 */
@HapiTestLifecycle
@OrderedInIsolation
public final class Tc01OrdinaryPayerDeletedTargetCreateTest {
    private static final long NODE = 2L;
    private static final long WHALE_STAKE = ONE_HUNDRED_HBARS;
    private static final long DIRECT_CREATE_STAKE = 25L * ONE_HBAR;
    private static final long MAX_STAKE_REWARDED = ONE_HUNDRED_HBARS;
    private static final long MAX_REWARD_RATE = 1_000L;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                overridingAllOf(Map.of(
                        "staking.startThreshold",
                        Long.toString(10 * ONE_HBAR),
                        "staking.perHbarRewardRate",
                        Long.toString(MAX_REWARD_RATE),
                        "staking.rewardBalanceThreshold",
                        "0",
                        "staking.maxStakeRewarded",
                        Long.toString(MAX_STAKE_REWARDED))),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)));
    }

    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    Stream<DynamicTest> createWithDeletedStakeeIsRejected() {
        final var attackerPayer = "tc01DirectCreatePayer";
        final var deleteBeneficiary = "tc01DirectCreateDeleteBeneficiary";
        final var deletedStakee = "tc01DirectCreateDeletedStakee";
        final var whale = "tc01DirectCreateWhale";
        final var directCreatedStaker = "tc01DirectCreatedStaker";

        return hapiTest(
                cryptoCreate(attackerPayer).balance(ONE_MILLION_HBARS),
                cryptoCreate(deleteBeneficiary)
                        .balance(0L)
                        .receiverSigRequired(false)
                        .payingWith(attackerPayer)
                        .signedBy(attackerPayer),
                cryptoCreate(whale)
                        .stakedNodeId(NODE)
                        .balance(WHALE_STAKE)
                        .payingWith(attackerPayer)
                        .signedBy(attackerPayer),

                // Create and immediately delete the proxy account.
                cryptoCreate(deletedStakee)
                        .stakedNodeId(NODE)
                        .balance(0L)
                        .payingWith(attackerPayer)
                        .signedBy(attackerPayer),
                cryptoDelete(deletedStakee)
                        .transfer(deleteBeneficiary)
                        .payingWith(attackerPayer)
                        .signedBy(attackerPayer, deletedStakee),

                // CryptoCreate with a deleted stakedAccountId must be rejected.
                cryptoCreate(directCreatedStaker)
                        .stakedAccountId(deletedStakee)
                        .balance(DIRECT_CREATE_STAKE)
                        .payingWith(attackerPayer)
                        .signedBy(attackerPayer)
                        .hasKnownStatus(INVALID_STAKING_ID),

                // The node's stakeToReward must reflect only the whale — the rejected create
                // must not have introduced phantom stake.
                doingContextual(spec -> Assertions.assertThat(nodeInfo(spec).stakeToReward())
                        .as("node stake must remain at the whale's 100 HBAR; no phantom stake from the rejected create")
                        .isEqualTo(WHALE_STAKE)));
    }

    private static com.hedera.services.bdd.spec.HapiSpecOperation triggerBoundary(@NonNull final String payer) {
        return cryptoTransfer(tinyBarsFromTo(payer, FUNDING, 1L))
                .payingWith(payer)
                .signedBy(payer);
    }

    private static StakingNodeInfo nodeInfo(@NonNull final HapiSpec spec) {
        final var info = spec.embeddedStakingInfosOrThrow()
                .get(EntityNumber.newBuilder().number(NODE).build());
        Assertions.assertThat(info).as("staking info for node %s", NODE).isNotNull();
        return info;
    }
}
