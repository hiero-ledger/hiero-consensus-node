// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
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

import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for a scheduled {@code ContractDelete} of a staked contract that executes as soon as it has every
 * required signature, either on the {@code ScheduleCreate} or on the {@code ScheduleSign} that adds the last one. The
 * contract's pending reward must be paid to the delete beneficiary from {@code 0.0.800} exactly once, and is recorded on
 * the triggering transaction rather than on the scheduled one.
 *
 * <p>Each test stakes the same principal for the same periods on a control contract (deleted directly), the contract
 * deleted through the schedule, and an honest staker, so each is owed the same reward, which the control delete
 * measures. {@code 0.0.800} is topped up to at least what the three could be owed at the full reward rate (exactly that
 * on a fresh network), so a second payment to the beneficiary would leave nothing for the honest staker.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
class ScheduledStakedContractDeleteRewardTest {
    private static final String PAYABLE_CONTRACT = "PayableConstructor";
    private static final long CONTRACT_CREATE_GAS = 1_000_000L;
    private static final long PER_HBAR_REWARD_RATE = 6849L;
    // Staking for three period boundaries earns rewards for two full periods
    private static final long REWARDED_PERIODS = 2L;
    // The reward is lower when earlier tests leave node 0 staked above its max stake
    private static final long MAX_REWARD = ONE_HUNDRED_HBARS / ONE_HBAR * PER_HBAR_REWARD_RATE * REWARDED_PERIODS;
    // At most owed to the control contract, the scheduled-delete contract, and the honest staker
    private static final long POOL = 3 * MAX_REWARD;

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> contractDeleteExecutedByScheduleCreatePaysRewardOnce() {
        return scheduledContractDeletePaysRewardOnce("byCreate", false);
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> contractDeleteExecutedByScheduleSignPaysRewardOnce() {
        return scheduledContractDeletePaysRewardOnce("bySign", true);
    }

    private static Stream<DynamicTest> scheduledContractDeletePaysRewardOnce(
            final String prefix, final boolean executeViaScheduleSign) {
        final var adminKey = prefix + "AdminKey";
        final var payer = prefix + "Payer";
        final var controlContract = prefix + "ControlContract";
        final var controlBeneficiary = prefix + "ControlBeneficiary";
        final var stakedContract = prefix + "StakedContract";
        final var beneficiary = prefix + "Beneficiary";
        final var honestStaker = prefix + "HonestStaker";
        final var honestInfoBefore = prefix + "HonestInfoBefore";
        final var honestInfoAfter = prefix + "HonestInfoAfter";
        final var schedule = prefix + "Schedule";
        final var createTxn = prefix + "ScheduleCreate";
        final var signTxn = prefix + "ScheduleSign";
        final var triggerTxn = executeViaScheduleSign ? signTxn : createTxn;

        final var poolBeforeTopUp = new AtomicLong();
        final var controlPrincipal = new AtomicLong();
        final var stakedPrincipal = new AtomicLong();
        final var rewardAccountStart = new AtomicLong();
        final var rewardAccountMid = new AtomicLong();
        final var rewardAccountEnd = new AtomicLong();
        final var rewardAccountFinal = new AtomicLong();
        final var pendingStart = new AtomicLong();
        final var pendingMid = new AtomicLong();
        final var pendingEnd = new AtomicLong();
        final var controlBeneficiaryBalance = new AtomicLong();
        final var beneficiaryBalance = new AtomicLong();
        final var honestBalanceBefore = new AtomicLong();
        final var honestBalanceAfter = new AtomicLong();
        final var scheduledRecord = new AtomicReference<TransactionRecord>();
        final var triggerRecord = new AtomicReference<TransactionRecord>();

        final var scheduledDelete = contractDelete(stakedContract).transferAccount(beneficiary);
        final SpecOperation[] executeScheduledDelete = executeViaScheduleSign
                ? new SpecOperation[] {
                    scheduleCreate(schedule, scheduledDelete).payingWith(payer).via(createTxn),
                    // Without the admin key's signature the delete waits for the ScheduleSign
                    getAccountInfo(beneficiary).has(accountWith().balance(0L)),
                    scheduleSign(schedule)
                            .alsoSigningWith(adminKey)
                            .payingWith(payer)
                            .via(signTxn)
                }
                : new SpecOperation[] {
                    scheduleCreate(schedule, scheduledDelete)
                            .alsoSigningWith(adminKey)
                            .payingWith(payer)
                            .via(createTxn)
                };

        return hapiTest(flattened(
                overridingAllOf(Map.of(
                        "staking.startThreshold",
                        "" + POOL,
                        "staking.perHbarRewardRate",
                        "" + PER_HBAR_REWARD_RATE,
                        "staking.rewardBalanceThreshold",
                        "0",
                        "staking.requireMinStakeToReward",
                        "false")),
                // Top 0.0.800 up to at least what the three stakers will be owed
                viewAccount(STAKING_REWARD, account -> poolBeforeTopUp.set(account.tinybarBalance())),
                sourcing(() -> poolBeforeTopUp.get() < POOL
                        ? cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, POOL - poolBeforeTopUp.get()))
                        : noOp()),
                newKeyNamed(adminKey),
                uploadInitCode(PAYABLE_CONTRACT),
                // Start all three stakers in the same staking period
                waitUntilStartOfNextStakingPeriod(1),
                stakedPayableContract(controlContract, adminKey),
                stakedPayableContract(stakedContract, adminKey),
                cryptoCreate(payer).balance(ONE_MILLION_HBARS),
                cryptoCreate(controlBeneficiary).balance(0L).receiverSigRequired(false),
                cryptoCreate(beneficiary).balance(0L).receiverSigRequired(false),
                cryptoCreate(honestStaker).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),

                // Accrue the same pending reward for all three stakers
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                viewContract(controlContract, a -> controlPrincipal.set(a.tinybarBalance())),
                viewContract(stakedContract, a -> stakedPrincipal.set(a.tinybarBalance())),
                viewAccount(STAKING_REWARD, account -> rewardAccountStart.set(account.tinybarBalance())),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> pendingStart.set(rewards.pendingRewards())),

                // Control: the same delete submitted directly
                contractDelete(controlContract)
                        .transferAccount(controlBeneficiary)
                        .payingWith(payer)
                        .signedBy(payer, adminKey),
                viewAccount(controlBeneficiary, account -> controlBeneficiaryBalance.set(account.tinybarBalance())),
                viewAccount(STAKING_REWARD, account -> rewardAccountMid.set(account.tinybarBalance())),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> pendingMid.set(rewards.pendingRewards())),

                // The delete through a schedule, executed as a SCHEDULED child of the triggering transaction
                executeScheduledDelete,
                getTxnRecord(createTxn)
                        .scheduled()
                        .hasPriority(recordWith().status(SUCCESS))
                        .exposingTo(scheduledRecord::set),
                getTxnRecord(triggerTxn).exposingTo(triggerRecord::set),
                viewAccount(beneficiary, account -> beneficiaryBalance.set(account.tinybarBalance())),
                viewAccount(STAKING_REWARD, account -> rewardAccountEnd.set(account.tinybarBalance())),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> pendingEnd.set(rewards.pendingRewards())),

                // The honest staker collects its reward from what is left in 0.0.800
                getAccountInfo(honestStaker).savingSnapshot(honestInfoBefore),
                viewAccount(honestStaker, account -> honestBalanceBefore.set(account.tinybarBalance())),
                cryptoTransfer(tinyBarsFromTo(honestStaker, FUNDING, 1L))
                        .payingWith(payer)
                        .signedBy(payer, honestStaker),
                viewAccount(honestStaker, account -> honestBalanceAfter.set(account.tinybarBalance())),
                getAccountInfo(honestStaker).savingSnapshot(honestInfoAfter),
                viewAccount(STAKING_REWARD, account -> rewardAccountFinal.set(account.tinybarBalance())),
                assertionsHold((spec, log) -> {
                    final var beneficiaryId = spec.registry().getAccountID(beneficiary);
                    final var rewardAccountId = spec.registry().getAccountID(STAKING_REWARD);

                    Assertions.assertThat(rewardAccountStart.get())
                            .as("precondition: 0.0.800 holds at least what the three stakers are owed")
                            .isGreaterThanOrEqualTo(POOL);
                    final long reward = controlBeneficiaryBalance.get() - controlPrincipal.get();
                    Assertions.assertThat(reward)
                            .as("control: the direct delete pays a reward")
                            .isPositive();
                    Assertions.assertThat(rewardAccountStart.get() - rewardAccountMid.get())
                            .as("control: 0.0.800 pays the direct delete's reward once")
                            .isEqualTo(reward);

                    Assertions.assertThat(beneficiaryBalance.get())
                            .as("beneficiary receives the contract's balance plus its reward exactly once")
                            .isEqualTo(stakedPrincipal.get() + reward);
                    Assertions.assertThat(rewardAccountMid.get() - rewardAccountEnd.get())
                            .as("0.0.800 pays the scheduled delete's reward exactly once")
                            .isEqualTo(reward);
                    Assertions.assertThat(pendingMid.get() - pendingEnd.get())
                            .as("pending rewards drop by the same amount as for the direct delete")
                            .isEqualTo(pendingStart.get() - pendingMid.get());
                    final var rewardToBeneficiary = AccountAmount.newBuilder()
                            .setAccountID(beneficiaryId)
                            .setAmount(reward)
                            .build();
                    Assertions.assertThat(triggerRecord.get().getPaidStakingRewardsList())
                            .as(
                                    "the triggering transaction records the contract's reward, redirected to the beneficiary")
                            .containsExactly(rewardToBeneficiary);
                    Assertions.assertThat(scheduledRecord.get().getPaidStakingRewardsList())
                            .as("the scheduled transaction records no staking rewards of its own")
                            .isEmpty();
                    Assertions.assertThat(triggerRecord.get().getTransferList().getAccountAmountsList())
                            .as("the triggering transaction's transfers move the reward from 0.0.800 once")
                            .contains(
                                    rewardToBeneficiary,
                                    AccountAmount.newBuilder()
                                            .setAccountID(rewardAccountId)
                                            .setAmount(-reward)
                                            .build());

                    Assertions.assertThat(spec.registry()
                                    .getAccountInfo(honestInfoBefore)
                                    .getStakingInfo()
                                    .getPendingReward())
                            .as("the honest staker is owed the same reward")
                            .isEqualTo(reward);
                    Assertions.assertThat(honestBalanceAfter.get() - honestBalanceBefore.get() + 1L)
                            .as("the honest staker collects its full reward")
                            .isEqualTo(reward);
                    Assertions.assertThat(spec.registry()
                                    .getAccountInfo(honestInfoAfter)
                                    .getStakingInfo()
                                    .getPendingReward())
                            .isZero();
                    Assertions.assertThat(rewardAccountStart.get() - rewardAccountFinal.get())
                            .as("0.0.800 paid exactly the three rewards it owed")
                            .isEqualTo(3 * reward);
                })));
    }

    private static HapiContractCreate stakedPayableContract(final String name, final String adminKey) {
        return contractCreate(name)
                .bytecode(PAYABLE_CONTRACT)
                .adminKey(adminKey)
                .stakedNodeId(0)
                .balance(ONE_HUNDRED_HBARS)
                .gas(CONTRACT_CREATE_GAS);
    }
}
