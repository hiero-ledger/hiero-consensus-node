// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Regression tests for the deleted-stakee freeze: deleting an account that an indirect staker
 * (HIP-406 {@code stakedAccountId}) points at must not permanently break that staker.
 *
 * <p>Before the fix, deleting a rewardable intermediate stakee left its {@code stakedNodeId} and
 * {@code stakedToMe} populated, so reward finalization in a <em>later</em> transaction rediscovered
 * the deleted stakee as a reward receiver, computed a positive reward for it, and then could not
 * redirect that reward (the delete-&gt;beneficiary mapping lives only on the deleting transaction's
 * record builder). {@code StakingRewardsDistributor#payRewardsIfPending} threw
 * {@code IllegalStateException}, the handle workflow rolled the savepoint back, and the transaction
 * was externalized as {@code FAIL_INVALID} — so any balance-changing transaction touching the staker
 * (outbound transfer, inbound transfer, or even clearing its staking election) failed the same way.
 *
 * <p>The fix ({@code StakingRewardsHandlerImpl#updateSpecialRewardReceivers}) skips a stakee that was
 * already deleted at the start of the transaction, so it is never rediscovered as a reward receiver.
 * These tests assert that the previously-frozen operations now succeed.
 */
@HapiTestLifecycle
public class RepeatableDeletedStakeeIndirectStakerTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                // These overrides only accelerate reward accrual so the scenario plays out in a few
                // virtual "days"; they are not required for the behavior under test.
                overridingAllOf(Map.of(
                        "staking.startThreshold", "" + 10 * ONE_HBAR,
                        "staking.perHbarRewardRate", "1",
                        "staking.rewardBalanceThreshold", "0")),
                // Fund 0.0.800 (STAKING_REWARD) so there is a pool to pay rewards from.
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    Stream<DynamicTest> indirectStakerCanTransactAfterItsRewardableStakeeIsDeleted() {
        return hapiTest(
                // "deletedStakee" stakes directly to node 0 and will be deleted;
                // "deletedStaker" stakes INDIRECTLY through it and used to get frozen.
                cryptoCreate("deletedStakee").stakedNodeId(0).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("deletedStaker").stakedAccountId("deletedStakee").balance(ONE_HUNDRED_HBARS),
                // CryptoDelete requires a transfer target for the deleted account's remaining balance.
                cryptoCreate("deletedStakeeBeneficiary").balance(0L),
                cryptoCreate("deletedStakerReceiver").balance(0L),

                // Roll forward two staking periods so the indirect staker becomes rewardable.
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),

                // Delete the rewardable intermediate stakee (deletedStaker still points at it).
                cryptoDelete("deletedStakee")
                        .transfer("deletedStakeeBeneficiary")
                        .payingWith("deletedStakee")
                        .via("deleteStakee")
                        .hasKnownStatus(SUCCESS),
                // The stakee really was rewardable: its accrued reward is redirected to the beneficiary on
                // delete. This guards against a future setup drift that would turn the scenario into a no-op,
                // and confirms the same-transaction redirect (which the fix preserves) still works.
                getTxnRecord("deleteStakee").hasPaidStakingRewardsCount(1),

                // Advance one more period so the deleted stakee would be "rewardable" again from the
                // perspective of a later transaction touching its indirect staker.
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),

                // The staker's outbound transfer now SUCCEEDS and the receiver is credited.
                cryptoTransfer(tinyBarsFromTo("deletedStaker", "deletedStakerReceiver", 1L))
                        .payingWith("deletedStaker")
                        .hasKnownStatus(SUCCESS),
                getAccountBalance("deletedStakerReceiver").hasTinyBars(1L),

                // The staker can also clear its staking election (newStakedNodeId(-1)) -> SUCCESS.
                cryptoUpdate("deletedStaker")
                        .newStakedNodeId(-1L)
                        .payingWith("deletedStaker")
                        .hasKnownStatus(SUCCESS));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    Stream<DynamicTest> thirdPartyCanTransferIntoIndirectStakerAfterItsRewardableStakeeIsDeleted() {
        // Proves a THIRD PARTY can also pay INTO the affected staker: crediting the receiver no longer
        // rediscovers the deleted stakee.
        return hapiTest(
                cryptoCreate("inboundDeletedStakee").stakedNodeId(0).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("inboundDeletedStaker")
                        .stakedAccountId("inboundDeletedStakee")
                        .balance(ONE_HUNDRED_HBARS),
                cryptoCreate("inboundDeletedStakeeBeneficiary").balance(0L),
                // A separate, unrelated sender (the "third party").
                cryptoCreate("inboundDeletedSender").balance(ONE_HUNDRED_HBARS),

                // Roll forward two periods so the indirect staker becomes rewardable.
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),

                // Delete the rewardable intermediate stakee.
                cryptoDelete("inboundDeletedStakee")
                        .transfer("inboundDeletedStakeeBeneficiary")
                        .payingWith("inboundDeletedStakee")
                        .via("inboundDeleteStakee")
                        .hasKnownStatus(SUCCESS),
                // The stakee really was rewardable: its accrued reward is redirected to the beneficiary on
                // delete (guards against the scenario silently becoming a no-op).
                getTxnRecord("inboundDeleteStakee").hasPaidStakingRewardsCount(1),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),

                // Third party pays INTO the affected staker -> SUCCESS, recipient is credited.
                cryptoTransfer(tinyBarsFromTo("inboundDeletedSender", "inboundDeletedStaker", 1L))
                        .payingWith("inboundDeletedSender")
                        .hasKnownStatus(SUCCESS),
                getAccountBalance("inboundDeletedStaker").hasTinyBars(ONE_HUNDRED_HBARS + 1L));
    }
}
