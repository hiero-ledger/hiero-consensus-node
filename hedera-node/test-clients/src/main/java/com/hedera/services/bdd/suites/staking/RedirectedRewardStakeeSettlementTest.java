// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.nOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoGetInfoResponse.AccountInfo;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for the settlement of an account that gains {@code stakedToMe} only because a
 * staking reward was redirected inside the same transaction.
 *
 * <p>When a staked account is deleted, its unpaid reward is paid to the beneficiary named by the
 * delete. If that beneficiary itself stakes to a third account, crediting it raises the third
 * account's {@code stakedToMe}. The reward-receiver snapshot that drives reward payment is taken
 * before any reward is paid, so the third account was never in a reward situation and its
 * {@code stakePeriodStart} was left where it was. Its still-open reward periods were then priced at
 * the new, larger stake, and the difference was paid out of the staking reward account (0.0.800)
 * for periods in which the added stake did not exist.
 *
 * <p>Reaching the bug needs the delete to move zero tinybars, otherwise the beneficiary's balance
 * change puts the third account into the snapshot on its own. An atomic batch does that without
 * relying on any fee constant: drain the earner in one inner transaction and delete it in the next,
 * so staking finalization runs once, at the root, with the earner already empty.
 *
 * <p>Both tests assert the fixed behavior: the third account is assessed and paid its own accrued
 * reward in the very same transaction, its stake metadata advances, and it collects nothing extra
 * afterwards -- the same outcome an ordinary transfer of the same amount would have produced.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
class RedirectedRewardStakeeSettlementTest {
    private static final long REWARD_RATE = 5_674L;
    private static final long EARNER_PRINCIPAL = 7L * ONE_HBAR;
    private static final long INDIRECT_STAKE = 5_000_000L * ONE_HBAR;
    private static final int OPEN_PERIODS = 4;

    /**
     * The exploit path. A drained, deleted earner's reward is redirected to a beneficiary that
     * stakes to an aggregator. The aggregator must be paid its own accrued reward in the same
     * transaction and must collect nothing for the periods that predate the redirect.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> redirectedRewardSettlesTheStakeeItInflates() {
        final String attacker = "settleAttacker";
        final String sink = "settleSink";
        final String deletedEarner = "settleDeletedEarner";
        final String earnerStaker = "settleEarnerStaker";
        final String beneficiary = "settleBeneficiary";
        final String aggregator = "settleAggregator";
        final String earnerBefore = "settleEarnerBefore";
        final String aggregatorBefore = "settleAggregatorBefore";
        final String aggregatorAfter = "settleAggregatorAfter";
        final String batchTxn = "settleBatch";
        final String touchTxn = "settleTouch";

        final var earnerPending = new AtomicLong();
        final var aggregatorPendingBefore = new AtomicLong();
        final var aggregatorPendingAfter = new AtomicLong();
        final var beneficiaryBalanceBefore = new AtomicLong();
        final var beneficiaryBalanceAfter = new AtomicLong();
        final var earnerStakerBalanceBefore = new AtomicLong();
        final var earnerStakerBalanceAfter = new AtomicLong();
        final var sinkBalanceBefore = new AtomicLong();
        final var sinkBalanceAfter = new AtomicLong();
        final var aggregatorBalanceBeforeTouch = new AtomicLong();
        final var aggregatorBalanceAfterTouch = new AtomicLong();
        final var aggregatorStakedToMeBefore = new AtomicLong();
        final var aggregatorStakedToMeAfter = new AtomicLong();
        final var aggregatorStakePeriodStartBefore = new AtomicLong();
        final var aggregatorStakePeriodStartAfter = new AtomicLong();
        final var aggregatorStakeAtStartBefore = new AtomicLong();
        final var aggregatorStakeAtStartAfter = new AtomicLong();
        final var aggregatorBalanceBefore = new AtomicLong();
        final var batchRecord = new AtomicReference<TransactionRecord>();
        final var touchRecord = new AtomicReference<TransactionRecord>();

        final var drain = cryptoTransfer(tinyBarsFromTo(deletedEarner, sink, EARNER_PRINCIPAL))
                .payingWith(attacker)
                .signedBy(attacker, deletedEarner)
                .batchKey(attacker);
        final var delete = cryptoDelete(deletedEarner)
                .transfer(beneficiary)
                .payingWith(attacker)
                .signedBy(attacker, deletedEarner)
                .batchKey(attacker);

        return hapiTest(flattened(
                acceleratedStakingOverrides(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)),
                cryptoCreate(attacker).balance(ONE_MILLION_HBARS),
                cryptoCreate(sink).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(aggregator).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                // Below one whole hbar, so the beneficiary contributes nothing to the aggregator's
                // stake until the redirect lands on it.
                cryptoCreate(beneficiary).balance(ONE_HBAR - 1L).stakedAccountId(aggregator),
                cryptoCreate(deletedEarner).balance(EARNER_PRINCIPAL).stakedNodeId(0),
                // The earner's reward comes almost entirely from this indirect stake, so the earner
                // can be drained to zero and still owe a large reward.
                cryptoCreate(earnerStaker).balance(INDIRECT_STAKE).stakedAccountId(deletedEarner),
                openRewardPeriods(),
                getAccountInfo(deletedEarner).savingSnapshot(earnerBefore),
                getAccountInfo(aggregator).savingSnapshot(aggregatorBefore),
                getAccountBalance(beneficiary).exposingBalanceTo(beneficiaryBalanceBefore::set),
                getAccountBalance(aggregator).exposingBalanceTo(aggregatorBalanceBefore::set),
                getAccountBalance(earnerStaker).exposingBalanceTo(earnerStakerBalanceBefore::set),
                getAccountBalance(sink).exposingBalanceTo(sinkBalanceBefore::set),
                viewAccount(aggregator, account -> {
                    aggregatorStakedToMeBefore.set(account.stakedToMe());
                    aggregatorStakePeriodStartBefore.set(account.stakePeriodStart());
                    aggregatorStakeAtStartBefore.set(account.stakeAtStartOfLastRewardedPeriod());
                }),
                assertionsHold((spec, log) -> {
                    earnerPending.set(pendingOf(spec.registry().getAccountInfo(earnerBefore)));
                    aggregatorPendingBefore.set(pendingOf(spec.registry().getAccountInfo(aggregatorBefore)));
                    // Setup guards: without a redirected reward and an aggregator with open
                    // periods there is nothing for this test to catch.
                    Assertions.assertThat(earnerPending.get()).isGreaterThanOrEqualTo(ONE_HBAR);
                    Assertions.assertThat(aggregatorPendingBefore.get()).isPositive();
                }),
                atomicBatch(drain, delete).payingWith(attacker).via(batchTxn),
                getTxnRecord(batchTxn).hasPriority(recordWith().status(SUCCESS)).exposingTo(batchRecord::set),
                getAccountBalance(beneficiary).exposingBalanceTo(beneficiaryBalanceAfter::set),
                getAccountBalance(sink).exposingBalanceTo(sinkBalanceAfter::set),
                getAccountBalance(earnerStaker).exposingBalanceTo(earnerStakerBalanceAfter::set),
                getAccountInfo(aggregator).savingSnapshot(aggregatorAfter),
                viewAccount(aggregator, account -> {
                    aggregatorStakedToMeAfter.set(account.stakedToMe());
                    aggregatorStakePeriodStartAfter.set(account.stakePeriodStart());
                    aggregatorStakeAtStartAfter.set(account.stakeAtStartOfLastRewardedPeriod());
                }),
                assertionsHold((spec, log) -> {
                    final var beneficiaryId = spec.registry().getAccountID(beneficiary);
                    final var aggregatorId = spec.registry().getAccountID(aggregator);
                    final var paid = batchRecord.get().getPaidStakingRewardsList();

                    // The drain and the redirect still work exactly as before.
                    Assertions.assertThat(sinkBalanceAfter.get() - sinkBalanceBefore.get())
                            .isEqualTo(EARNER_PRINCIPAL);
                    Assertions.assertThat(beneficiaryBalanceAfter.get() - beneficiaryBalanceBefore.get())
                            .isEqualTo(earnerPending.get());
                    Assertions.assertThat(amountPaidTo(paid, beneficiaryId)).isEqualTo(earnerPending.get());

                    // The aggregator is now assessed in the same transaction, and is paid exactly
                    // what it had accrued before the redirect -- no more.
                    Assertions.assertThat(paid).hasSize(2);
                    Assertions.assertThat(amountPaidTo(paid, aggregatorId)).isEqualTo(aggregatorPendingBefore.get());

                    // Its stake did go up, so the settlement had to happen first.
                    final long wholeHbarsAdded =
                            (beneficiaryBalanceAfter.get() / ONE_HBAR) - (beneficiaryBalanceBefore.get() / ONE_HBAR);
                    Assertions.assertThat(wholeHbarsAdded).isPositive();
                    Assertions.assertThat(aggregatorStakedToMeAfter.get() - aggregatorStakedToMeBefore.get())
                            .isEqualTo(wholeHbarsAdded * ONE_HBAR);

                    // Stake metadata advanced, and remembers the stake from BEFORE the redirect.
                    Assertions.assertThat(aggregatorStakePeriodStartAfter.get())
                            .isGreaterThan(aggregatorStakePeriodStartBefore.get());
                    Assertions.assertThat(aggregatorStakeAtStartAfter.get())
                            .isNotEqualTo(aggregatorStakeAtStartBefore.get())
                            .isEqualTo(wholeHbars(aggregatorBalanceBefore.get() + aggregatorStakedToMeBefore.get()));

                    // Nothing is left over for the added stake to be priced against.
                    aggregatorPendingAfter.set(pendingOf(spec.registry().getAccountInfo(aggregatorAfter)));
                    Assertions.assertThat(aggregatorPendingAfter.get()).isZero();

                    // The orphaned indirect staker is not touched, as before.
                    Assertions.assertThat(earnerStakerBalanceAfter.get()).isEqualTo(earnerStakerBalanceBefore.get());
                }),
                getAccountBalance(aggregator).exposingBalanceTo(aggregatorBalanceBeforeTouch::set),
                // The follow-up transaction that used to collect the retroactive overpayment.
                cryptoTransfer(tinyBarsFromTo(attacker, aggregator, 1L))
                        .payingWith(attacker)
                        .signedBy(attacker)
                        .via(touchTxn),
                getTxnRecord(touchTxn).hasPriority(recordWith().status(SUCCESS)).exposingTo(touchRecord::set),
                getAccountBalance(aggregator).exposingBalanceTo(aggregatorBalanceAfterTouch::set),
                assertionsHold((spec, log) -> {
                    // The aggregator gains the transferred tinybar and nothing else: no reward is
                    // paid for the periods that predate the redirected stake.
                    Assertions.assertThat(aggregatorBalanceAfterTouch.get() - aggregatorBalanceBeforeTouch.get())
                            .isEqualTo(1L);
                    Assertions.assertThat(totalPaid(touchRecord.get().getPaidStakingRewardsList()))
                            .isZero();
                })));
    }

    /**
     * Parity with the ordinary path. Delivering the same amount to an equivalent beneficiary by a
     * plain transfer has always settled that beneficiary's stakee; a redirect must now leave the
     * stakee in the same state, down to the reward paid and both stake-metadata fields.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> redirectSettlesTheStakeeJustLikeAnOrdinaryTransfer() {
        final String funder = "parityFunder";
        final String sink = "paritySink";
        final String deletedEarner = "parityDeletedEarner";
        final String earnerStaker = "parityEarnerStaker";
        final String beneficiary = "parityBeneficiary";
        final String aggregator = "parityAggregator";
        final String controlBeneficiary = "parityControlBeneficiary";
        final String controlAggregator = "parityControlAggregator";
        final String earnerBefore = "parityEarnerBefore";
        final String batchTxn = "parityBatch";
        final String controlTxn = "parityControlTransfer";

        final var earnerPending = new AtomicLong();
        final var beneficiaryBalanceAfter = new AtomicLong();
        final var controlBeneficiaryBalanceAfter = new AtomicLong();
        final var stakedToMeAfter = new AtomicLong();
        final var stakePeriodStartAfter = new AtomicLong();
        final var stakeAtStartAfter = new AtomicLong();
        final var controlStakedToMeAfter = new AtomicLong();
        final var controlStakePeriodStartAfter = new AtomicLong();
        final var controlStakeAtStartAfter = new AtomicLong();
        final var batchRecord = new AtomicReference<TransactionRecord>();
        final var controlRecord = new AtomicReference<TransactionRecord>();

        final var drain = cryptoTransfer(tinyBarsFromTo(deletedEarner, sink, EARNER_PRINCIPAL))
                .payingWith(funder)
                .signedBy(funder, deletedEarner)
                .batchKey(funder);
        final var delete = cryptoDelete(deletedEarner)
                .transfer(beneficiary)
                .payingWith(funder)
                .signedBy(funder, deletedEarner)
                .batchKey(funder);

        return hapiTest(flattened(
                acceleratedStakingOverrides(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)),
                cryptoCreate(funder).balance(ONE_MILLION_HBARS),
                cryptoCreate(sink).balance(ONE_HUNDRED_HBARS),
                // The two aggregator/beneficiary pairs are created identically, so they accrue the
                // same reward over the same periods.
                cryptoCreate(aggregator).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                cryptoCreate(beneficiary).balance(ONE_HBAR - 1L).stakedAccountId(aggregator),
                cryptoCreate(controlAggregator).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                cryptoCreate(controlBeneficiary).balance(ONE_HBAR - 1L).stakedAccountId(controlAggregator),
                cryptoCreate(deletedEarner).balance(EARNER_PRINCIPAL).stakedNodeId(0),
                cryptoCreate(earnerStaker).balance(INDIRECT_STAKE).stakedAccountId(deletedEarner),
                openRewardPeriods(),
                getAccountInfo(deletedEarner).savingSnapshot(earnerBefore),
                assertionsHold((spec, log) -> {
                    earnerPending.set(pendingOf(spec.registry().getAccountInfo(earnerBefore)));
                    Assertions.assertThat(earnerPending.get()).isGreaterThanOrEqualTo(ONE_HBAR);
                }),
                atomicBatch(drain, delete).payingWith(funder).via(batchTxn),
                getTxnRecord(batchTxn).hasPriority(recordWith().status(SUCCESS)).exposingTo(batchRecord::set),
                getAccountBalance(beneficiary).exposingBalanceTo(beneficiaryBalanceAfter::set),
                viewAccount(aggregator, account -> {
                    stakedToMeAfter.set(account.stakedToMe());
                    stakePeriodStartAfter.set(account.stakePeriodStart());
                    stakeAtStartAfter.set(account.stakeAtStartOfLastRewardedPeriod());
                }),
                // Deliver the identical amount to the control beneficiary the ordinary way.
                sourcing(() -> cryptoTransfer(tinyBarsFromTo(funder, controlBeneficiary, earnerPending.get()))
                        .payingWith(funder)
                        .signedBy(funder)
                        .via(controlTxn)),
                getTxnRecord(controlTxn)
                        .hasPriority(recordWith().status(SUCCESS))
                        .exposingTo(controlRecord::set),
                getAccountBalance(controlBeneficiary).exposingBalanceTo(controlBeneficiaryBalanceAfter::set),
                viewAccount(controlAggregator, account -> {
                    controlStakedToMeAfter.set(account.stakedToMe());
                    controlStakePeriodStartAfter.set(account.stakePeriodStart());
                    controlStakeAtStartAfter.set(account.stakeAtStartOfLastRewardedPeriod());
                }),
                assertionsHold((spec, log) -> {
                    final var aggregatorId = spec.registry().getAccountID(aggregator);
                    final var controlAggregatorId = spec.registry().getAccountID(controlAggregator);

                    // Both beneficiaries received the same amount, so both aggregators gained the
                    // same stake.
                    Assertions.assertThat(controlBeneficiaryBalanceAfter.get())
                            .isEqualTo(beneficiaryBalanceAfter.get());
                    Assertions.assertThat(stakedToMeAfter.get()).isEqualTo(controlStakedToMeAfter.get());

                    // And both were settled identically.
                    Assertions.assertThat(amountPaidTo(batchRecord.get().getPaidStakingRewardsList(), aggregatorId))
                            .isEqualTo(
                                    amountPaidTo(controlRecord.get().getPaidStakingRewardsList(), controlAggregatorId))
                            .isPositive();
                    Assertions.assertThat(stakePeriodStartAfter.get()).isEqualTo(controlStakePeriodStartAfter.get());
                    Assertions.assertThat(stakeAtStartAfter.get()).isEqualTo(controlStakeAtStartAfter.get());
                })));
    }

    /**
     * Both fixes in this PR, on a real network, in one atomic batch. One deleted earner redirects
     * to an account-staked beneficiary, which forces the settlement of that beneficiary's
     * aggregator (BB-93847). A second deleted earner redirects to an untouched node-staked
     * beneficiary, which the distributor must enqueue and assess for its own reward (#1642).
     *
     * <p>Each of the three recipients must be credited exactly what it is owed, both assessed
     * accounts must advance their stake metadata, and neither may collect anything afterwards.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> bothRedirectFixesActInOneAtomicBatch() {
        final String actor = "bothActor";
        final String sink = "bothSink";
        final String bigEarner = "bothBigEarner";
        final String earnerStaker = "bothEarnerStaker";
        final String smallEarner = "bothSmallEarner";
        final String accountStakedBeneficiary = "bothAccountStakedBeneficiary";
        final String aggregator = "bothAggregator";
        final String nodeStakedBeneficiary = "bothNodeStakedBeneficiary";
        final String bigEarnerBefore = "bothBigEarnerBefore";
        final String smallEarnerBefore = "bothSmallEarnerBefore";
        final String aggregatorBefore = "bothAggregatorBefore";
        final String nodeBeneficiaryBefore = "bothNodeBeneficiaryBefore";
        final String batchTxn = "bothBatch";
        final String touchAggregatorTxn = "bothTouchAggregator";
        final String touchBeneficiaryTxn = "bothTouchBeneficiary";

        final var bigEarnerPending = new AtomicLong();
        final var smallEarnerPending = new AtomicLong();
        final var aggregatorPending = new AtomicLong();
        final var nodeBeneficiaryPending = new AtomicLong();
        final var aggregatorBalanceBefore = new AtomicLong();
        final var nodeBeneficiaryBalanceBefore = new AtomicLong();
        final var aggregatorStakedToMeBefore = new AtomicLong();
        final var aggregatorStakePeriodStartBefore = new AtomicLong();
        final var nodeBeneficiaryStakePeriodStartBefore = new AtomicLong();
        final var aggregatorStakePeriodStartAfter = new AtomicLong();
        final var nodeBeneficiaryStakePeriodStartAfter = new AtomicLong();
        final var aggregatorStakedToMeAfter = new AtomicLong();
        final var aggregatorBalanceAfterBatch = new AtomicLong();
        final var nodeBeneficiaryBalanceAfterBatch = new AtomicLong();
        final var aggregatorBalanceAfterTouch = new AtomicLong();
        final var nodeBeneficiaryBalanceAfterTouch = new AtomicLong();
        final var batchRecord = new AtomicReference<TransactionRecord>();
        final var touchAggregatorRecord = new AtomicReference<TransactionRecord>();
        final var touchBeneficiaryRecord = new AtomicReference<TransactionRecord>();

        final var drainBig = cryptoTransfer(tinyBarsFromTo(bigEarner, sink, EARNER_PRINCIPAL))
                .payingWith(actor)
                .signedBy(actor, bigEarner)
                .batchKey(actor);
        final var deleteBig = cryptoDelete(bigEarner)
                .transfer(accountStakedBeneficiary)
                .payingWith(actor)
                .signedBy(actor, bigEarner)
                .batchKey(actor);
        final var drainSmall = cryptoTransfer(tinyBarsFromTo(smallEarner, sink, EARNER_PRINCIPAL))
                .payingWith(actor)
                .signedBy(actor, smallEarner)
                .batchKey(actor);
        final var deleteSmall = cryptoDelete(smallEarner)
                .transfer(nodeStakedBeneficiary)
                .payingWith(actor)
                .signedBy(actor, smallEarner)
                .batchKey(actor);

        return hapiTest(flattened(
                acceleratedStakingOverrides(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)),
                cryptoCreate(actor).balance(ONE_MILLION_HBARS),
                cryptoCreate(sink).balance(ONE_HUNDRED_HBARS),
                // BB-93847 side: the redirect lands on an account-staked beneficiary, so the
                // aggregator behind it must be settled before its stakedToMe goes up.
                cryptoCreate(aggregator).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                cryptoCreate(accountStakedBeneficiary).balance(ONE_HBAR - 1L).stakedAccountId(aggregator),
                // #1642 side: the redirect lands on an untouched node-staked beneficiary, which
                // must be assessed for its own accrued reward rather than forfeiting it.
                cryptoCreate(nodeStakedBeneficiary).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                cryptoCreate(bigEarner).balance(EARNER_PRINCIPAL).stakedNodeId(0),
                cryptoCreate(earnerStaker).balance(INDIRECT_STAKE).stakedAccountId(bigEarner),
                cryptoCreate(smallEarner).balance(EARNER_PRINCIPAL).stakedNodeId(0),
                openRewardPeriods(),
                getAccountInfo(bigEarner).savingSnapshot(bigEarnerBefore),
                getAccountInfo(smallEarner).savingSnapshot(smallEarnerBefore),
                getAccountInfo(aggregator).savingSnapshot(aggregatorBefore),
                getAccountInfo(nodeStakedBeneficiary).savingSnapshot(nodeBeneficiaryBefore),
                getAccountBalance(aggregator).exposingBalanceTo(aggregatorBalanceBefore::set),
                getAccountBalance(nodeStakedBeneficiary).exposingBalanceTo(nodeBeneficiaryBalanceBefore::set),
                viewAccount(aggregator, account -> {
                    aggregatorStakedToMeBefore.set(account.stakedToMe());
                    aggregatorStakePeriodStartBefore.set(account.stakePeriodStart());
                }),
                viewAccount(
                        nodeStakedBeneficiary,
                        account -> nodeBeneficiaryStakePeriodStartBefore.set(account.stakePeriodStart())),
                assertionsHold((spec, log) -> {
                    bigEarnerPending.set(pendingOf(spec.registry().getAccountInfo(bigEarnerBefore)));
                    smallEarnerPending.set(pendingOf(spec.registry().getAccountInfo(smallEarnerBefore)));
                    aggregatorPending.set(pendingOf(spec.registry().getAccountInfo(aggregatorBefore)));
                    nodeBeneficiaryPending.set(pendingOf(spec.registry().getAccountInfo(nodeBeneficiaryBefore)));
                    // Setup guards: all four accounts must really have something at stake.
                    Assertions.assertThat(bigEarnerPending.get()).isGreaterThanOrEqualTo(ONE_HBAR);
                    Assertions.assertThat(smallEarnerPending.get()).isPositive();
                    Assertions.assertThat(aggregatorPending.get()).isPositive();
                    Assertions.assertThat(nodeBeneficiaryPending.get()).isPositive();
                }),
                atomicBatch(drainBig, deleteBig, drainSmall, deleteSmall)
                        .payingWith(actor)
                        .via(batchTxn),
                getTxnRecord(batchTxn).hasPriority(recordWith().status(SUCCESS)).exposingTo(batchRecord::set),
                getAccountBalance(aggregator).exposingBalanceTo(aggregatorBalanceAfterBatch::set),
                getAccountBalance(nodeStakedBeneficiary).exposingBalanceTo(nodeBeneficiaryBalanceAfterBatch::set),
                viewAccount(aggregator, account -> {
                    aggregatorStakedToMeAfter.set(account.stakedToMe());
                    aggregatorStakePeriodStartAfter.set(account.stakePeriodStart());
                }),
                viewAccount(
                        nodeStakedBeneficiary,
                        account -> nodeBeneficiaryStakePeriodStartAfter.set(account.stakePeriodStart())),
                assertionsHold((spec, log) -> {
                    final var paid = batchRecord.get().getPaidStakingRewardsList();
                    final var accountStakedId = spec.registry().getAccountID(accountStakedBeneficiary);
                    final var nodeStakedId = spec.registry().getAccountID(nodeStakedBeneficiary);
                    final var aggregatorId = spec.registry().getAccountID(aggregator);

                    // Three recipients: the account-staked beneficiary (redirect only), the
                    // node-staked beneficiary (redirect plus its own reward, aggregated into one
                    // entry), and the aggregator (its own reward).
                    Assertions.assertThat(paid).hasSize(3);
                    Assertions.assertThat(amountPaidTo(paid, accountStakedId)).isEqualTo(bigEarnerPending.get());
                    Assertions.assertThat(amountPaidTo(paid, nodeStakedId))
                            .isEqualTo(smallEarnerPending.get() + nodeBeneficiaryPending.get());
                    Assertions.assertThat(amountPaidTo(paid, aggregatorId)).isEqualTo(aggregatorPending.get());

                    // Balances match the record.
                    Assertions.assertThat(aggregatorBalanceAfterBatch.get() - aggregatorBalanceBefore.get())
                            .isEqualTo(aggregatorPending.get());
                    Assertions.assertThat(nodeBeneficiaryBalanceAfterBatch.get() - nodeBeneficiaryBalanceBefore.get())
                            .isEqualTo(smallEarnerPending.get() + nodeBeneficiaryPending.get());

                    // The aggregator's stake really did rise, and it was settled first.
                    Assertions.assertThat(aggregatorStakedToMeAfter.get())
                            .isGreaterThan(aggregatorStakedToMeBefore.get());
                    Assertions.assertThat(aggregatorStakePeriodStartAfter.get())
                            .isGreaterThan(aggregatorStakePeriodStartBefore.get());
                    Assertions.assertThat(nodeBeneficiaryStakePeriodStartAfter.get())
                            .isGreaterThan(nodeBeneficiaryStakePeriodStartBefore.get());
                }),
                // Neither assessed account may collect anything more.
                cryptoTransfer(tinyBarsFromTo(actor, aggregator, 1L))
                        .payingWith(actor)
                        .signedBy(actor)
                        .via(touchAggregatorTxn),
                cryptoTransfer(tinyBarsFromTo(actor, nodeStakedBeneficiary, 1L))
                        .payingWith(actor)
                        .signedBy(actor)
                        .via(touchBeneficiaryTxn),
                getTxnRecord(touchAggregatorTxn).exposingTo(touchAggregatorRecord::set),
                getTxnRecord(touchBeneficiaryTxn).exposingTo(touchBeneficiaryRecord::set),
                getAccountBalance(aggregator).exposingBalanceTo(aggregatorBalanceAfterTouch::set),
                getAccountBalance(nodeStakedBeneficiary).exposingBalanceTo(nodeBeneficiaryBalanceAfterTouch::set),
                assertionsHold((spec, log) -> {
                    Assertions.assertThat(totalPaid(touchAggregatorRecord.get().getPaidStakingRewardsList()))
                            .isZero();
                    Assertions.assertThat(totalPaid(touchBeneficiaryRecord.get().getPaidStakingRewardsList()))
                            .isZero();
                    Assertions.assertThat(aggregatorBalanceAfterTouch.get() - aggregatorBalanceAfterBatch.get())
                            .isEqualTo(1L);
                    Assertions.assertThat(
                                    nodeBeneficiaryBalanceAfterTouch.get() - nodeBeneficiaryBalanceAfterBatch.get())
                            .isEqualTo(1L);
                })));
    }

    /**
     * Accelerates reward accrual so the scenario plays out in a few virtual days. These values do
     * not affect the behavior under test.
     */
    private static HapiSpecOperation acceleratedStakingOverrides() {
        return overridingAllOf(Map.of(
                "staking.startThreshold",
                "" + 10 * ONE_HBAR,
                "staking.perHbarRewardRate",
                "" + REWARD_RATE,
                "staking.rewardBalanceThreshold",
                "0",
                "staking.requireMinStakeToReward",
                "false"));
    }

    /**
     * Rolls virtual time forward far enough to leave every staker with several unpaid reward
     * periods. Each period needs one transaction to make the network observe the new period.
     */
    private static HapiSpecOperation openRewardPeriods() {
        return blockingOrder(nOps(
                OPEN_PERIODS + 1,
                i -> blockingOrder(
                        waitUntilStartOfNextStakingPeriod(1), cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)))));
    }

    private static long pendingOf(final AccountInfo info) {
        return info.getStakingInfo().getPendingReward();
    }

    private static long wholeHbars(final long tinybars) {
        return (tinybars / ONE_HBAR) * ONE_HBAR;
    }

    private static long amountPaidTo(final List<AccountAmount> paidStakingRewards, final AccountID id) {
        return paidStakingRewards.stream()
                .filter(aa -> aa.getAccountID().equals(id))
                .mapToLong(AccountAmount::getAmount)
                .sum();
    }

    private static long totalPaid(final List<AccountAmount> paidStakingRewards) {
        return paidStakingRewards.stream().mapToLong(AccountAmount::getAmount).sum();
    }
}
