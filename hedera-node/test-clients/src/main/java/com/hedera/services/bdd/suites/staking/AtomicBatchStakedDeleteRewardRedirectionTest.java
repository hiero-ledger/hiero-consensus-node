// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
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
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.CryptoGetInfoResponse.AccountInfo;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression test for staking-reward finalization when a staked account is deleted inside an atomic
 * batch (HIP-551). For an inner-batch delete, the deleted-account to beneficiary mapping is recorded
 * on the child dispatch builder rather than the root builder that staking finalization consults, so
 * without the read-side fold the redirect loop cannot resolve the beneficiary and finalization fails.
 *
 * <p>The batch runs fifteen fee-bearing storage-writing contract calls followed by a valid staked
 * delete, and asserts the correct behavior: the batch succeeds, the deleted account's pending reward
 * is redirected to its beneficiary, every inner record persists, and fees are charged.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
class AtomicBatchStakedDeleteRewardRedirectionTest {
    // A contract with a payable constructor, so the staked contract can be created holding an HBAR balance.
    private static final String PAYABLE_CONTRACT = "PayableConstructor";
    private static final String BATCH_KEY = "rewardRedirectBatchKey";
    private static final String WORK_PAYER = "rewardRedirectWorkPayer";
    private static final String OUTER_PAYER = "rewardRedirectOuterPayer";
    private static final String STAKED_ACCOUNT = "rewardRedirectStakedAccount";
    private static final String DELETE_BENEFICIARY = "rewardRedirectBeneficiary";
    private static final String WORK_PREFIX = "rewardRedirectWork";
    private static final String DELETE_INNER = "rewardRedirectDeleteInner";
    private static final String DELETE_BATCH = "rewardRedirectBatch";
    private static final String STAKED_BEFORE = "rewardRedirectStakedBefore";

    private static final String CONTRACT_ADMIN_KEY = "rewardRedirectContractAdminKey";
    private static final String STAKED_CONTRACT = "rewardRedirectStakedContract";
    private static final String CONTRACT_DELETE_BENEFICIARY = "rewardRedirectContractBeneficiary";
    private static final String CONTRACT_DELETE_PAYER = "rewardRedirectContractDeletePayer";
    private static final String CONTRACT_OUTER_PAYER = "rewardRedirectContractOuterPayer";
    private static final String CONTRACT_DELETE_INNER = "rewardRedirectContractDeleteInner";
    private static final String CONTRACT_BATCH = "rewardRedirectContractBatch";

    // A payable contract that self-destructs (EVM SELFDESTRUCT) to msg.sender via its {@code destroy()} method.
    private static final String SELF_DESTRUCT_CONTRACT = "SelfDestructCallable";
    private static final String SD_WORK_PAYER = "rewardRedirectSdWorkPayer";
    private static final String SD_CALL_PAYER = "rewardRedirectSdCallPayer";
    private static final String SD_OUTER_PAYER = "rewardRedirectSdOuterPayer";
    private static final String SD_INNER = "rewardRedirectSdInner";
    private static final String SD_BATCH = "rewardRedirectSdBatch";

    private static final int WORK_INNER_COUNT = 15;
    private static final long GAS_LIMIT = 500_000L;
    private static final long CONTRACT_CREATE_GAS = 1_000_000L;

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> stakedDeleteInAtomicBatchRedirectsRewardAndChargesFees() {
        final var stakedPendingReward = new AtomicLong();
        final var stakedBalanceBefore = new AtomicLong();
        final var beneficiaryBefore = new AtomicLong();
        final var beneficiaryAfter = new AtomicLong();
        final var workPayerBefore = new AtomicLong();
        final var workPayerAfter = new AtomicLong();
        final var outerPayerBefore = new AtomicLong();
        final var outerPayerAfter = new AtomicLong();
        final var networkPendingBefore = new AtomicLong();
        final var networkPendingAfter = new AtomicLong();
        final var batchRecord = new AtomicReference<TransactionRecord>();
        final var deleteRecord = new AtomicReference<TransactionRecord>();

        final var deleteInner = cryptoDelete(STAKED_ACCOUNT)
                .transfer(DELETE_BENEFICIARY)
                .payingWith(STAKED_ACCOUNT)
                .signedBy(STAKED_ACCOUNT)
                .memo("t")
                .batchKey(BATCH_KEY)
                .via(DELETE_INNER);
        final var batch = atomicBatch(workThen(WORK_PAYER, deleteInner))
                .payingWith(OUTER_PAYER)
                .signedBy(OUTER_PAYER, BATCH_KEY)
                .memo("b")
                .via(DELETE_BATCH)
                .hasKnownStatus(SUCCESS);

        return hapiTest(flattened(
                // Activate staking rewards with a payable reward fund.
                overridingAllOf(java.util.Map.of(
                        "staking.startThreshold", "" + 10 * ONE_HBAR,
                        "staking.perHbarRewardRate", "6849",
                        "staking.rewardBalanceThreshold", "0",
                        "staking.requireMinStakeToReward", "false")),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)),
                newKeyNamed(BATCH_KEY),
                cryptoCreate(WORK_PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(OUTER_PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(STAKED_ACCOUNT).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                cryptoCreate(DELETE_BENEFICIARY).balance(0L).receiverSigRequired(false),

                // Accrue a positive, payable pending reward for the staked account.
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                getAccountInfo(STAKED_ACCOUNT).savingSnapshot(STAKED_BEFORE),
                assertionsHold((spec, log) -> {
                    final AccountInfo stakedAccount = spec.registry().getAccountInfo(STAKED_BEFORE);
                    stakedPendingReward.set(pendingOf(stakedAccount));
                    Assertions.assertThat(stakedPendingReward.get())
                            .as("precondition: staked account must have a positive pending reward")
                            .isPositive();
                }),

                // Snapshot everything the fixed path should change.
                getAccountBalance(WORK_PAYER).exposingBalanceTo(workPayerBefore::set),
                getAccountBalance(OUTER_PAYER).exposingBalanceTo(outerPayerBefore::set),
                getAccountBalance(STAKED_ACCOUNT).exposingBalanceTo(stakedBalanceBefore::set),
                getAccountBalance(DELETE_BENEFICIARY).exposingBalanceTo(beneficiaryBefore::set),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> networkPendingBefore.set(rewards.pendingRewards())),

                // Fifteen fee-bearing storage-writing transfers, then the valid staked delete.
                batch,

                // The batch succeeds. Staking rewards are finalized at the root, so the redirected reward
                // is externalized on the outer batch record (exactly one), not on the inner delete record.
                getTxnRecord(DELETE_BATCH)
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasPaidStakingRewardsCount(1)
                        .exposingTo(batchRecord::set),
                getTxnRecord(DELETE_INNER)
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasPaidStakingRewardsCount(0)
                        .exposingTo(deleteRecord::set),
                getAccountBalance(WORK_PAYER).exposingBalanceTo(workPayerAfter::set),
                getAccountBalance(OUTER_PAYER).exposingBalanceTo(outerPayerAfter::set),
                getAccountBalance(DELETE_BENEFICIARY).exposingBalanceTo(beneficiaryAfter::set),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> networkPendingAfter.set(rewards.pendingRewards())),
                workRecordsPresent(),
                assertionsHold((spec, log) -> {
                    // Fees are charged for the inner work and the outer batch.
                    Assertions.assertThat(workPayerBefore.get() - workPayerAfter.get())
                            .as("the fifteen fee-bearing transfers must charge the work payer")
                            .isPositive();
                    Assertions.assertThat(outerPayerBefore.get() - outerPayerAfter.get())
                            .as("the outer batch fee must be charged")
                            .isPositive();
                    Assertions.assertThat(batchRecord.get().getTransactionFee()).isPositive();

                    // The pending reward was redirected to the (non-deleted) beneficiary: it receives the
                    // deleted account's remaining principal (its pre-delete balance minus the delete fee it
                    // paid as the inner payer) plus the redirected reward, and the network pending reward drops.
                    final long beneficiaryGain = beneficiaryAfter.get() - beneficiaryBefore.get();
                    final long deleteFee = deleteRecord.get().getTransactionFee();
                    Assertions.assertThat(beneficiaryGain)
                            .as("beneficiary receives the deleted account's principal (pre-delete balance minus "
                                    + "its own delete fee) plus its redirected reward")
                            .isEqualTo(stakedBalanceBefore.get() - deleteFee + stakedPendingReward.get());
                    Assertions.assertThat(networkPendingBefore.get() - networkPendingAfter.get())
                            .as("the network pending reward must decrease by the reward actually paid out")
                            .isPositive();

                    // Record-level proof of the redirect: the batch record externalizes exactly one paid
                    // staking reward, to the delete beneficiary, for the deleted account's pending amount.
                    final var paidRewards = batchRecord.get().getPaidStakingRewardsList();
                    Assertions.assertThat(paidRewards)
                            .as("the batch record externalizes exactly one paid staking reward")
                            .hasSize(1);
                    Assertions.assertThat(paidRewards.get(0).getAccountID())
                            .as("the paid staking reward is attributed to the delete beneficiary")
                            .isEqualTo(spec.registry().getAccountID(DELETE_BENEFICIARY));
                    Assertions.assertThat(paidRewards.get(0).getAmount())
                            .as("the paid reward equals the deleted account's pending reward")
                            .isEqualTo(stakedPendingReward.get());
                    log.info(
                            "PASS: batch SUCCESS, reward {} redirected to beneficiary, fees charged",
                            stakedPendingReward.get());
                })));
    }

    /**
     * Same fixed behavior as {@link #stakedDeleteInAtomicBatchRedirectsRewardAndChargesFees()} but via an inner
     * {@code ContractDelete} of a staked contract, exercising the {@code ContractDelete} path of the fix (which
     * records its beneficiary on its own dispatch builder exactly like {@code CryptoDelete}).
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> stakedContractDeleteInAtomicBatchRedirectsRewardAndChargesFees() {
        final var beneficiaryBefore = new AtomicLong();
        final var beneficiaryAfter = new AtomicLong();
        final var outerPayerBefore = new AtomicLong();
        final var outerPayerAfter = new AtomicLong();
        final var networkPendingBefore = new AtomicLong();
        final var networkPendingAfter = new AtomicLong();
        final var batchRecord = new AtomicReference<TransactionRecord>();

        final var contractDeleteInner = contractDelete(STAKED_CONTRACT)
                .transferAccount(CONTRACT_DELETE_BENEFICIARY)
                .payingWith(CONTRACT_DELETE_PAYER)
                .signedBy(CONTRACT_DELETE_PAYER, CONTRACT_ADMIN_KEY)
                .memo("t")
                .batchKey(BATCH_KEY)
                .via(CONTRACT_DELETE_INNER);
        final var batch = atomicBatch(workThen(CONTRACT_DELETE_PAYER, contractDeleteInner))
                .payingWith(CONTRACT_OUTER_PAYER)
                .signedBy(CONTRACT_OUTER_PAYER, BATCH_KEY)
                .memo("b")
                .via(CONTRACT_BATCH)
                .hasKnownStatus(SUCCESS);

        return hapiTest(flattened(
                overridingAllOf(java.util.Map.of(
                        "staking.startThreshold", "" + 10 * ONE_HBAR,
                        "staking.perHbarRewardRate", "6849",
                        "staking.rewardBalanceThreshold", "0",
                        "staking.requireMinStakeToReward", "false")),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)),
                newKeyNamed(BATCH_KEY),
                newKeyNamed(CONTRACT_ADMIN_KEY),
                uploadInitCode(PAYABLE_CONTRACT),
                contractCreate(STAKED_CONTRACT)
                        .bytecode(PAYABLE_CONTRACT)
                        .adminKey(CONTRACT_ADMIN_KEY)
                        .stakedNodeId(0)
                        .balance(ONE_HUNDRED_HBARS)
                        .gas(CONTRACT_CREATE_GAS),
                cryptoCreate(CONTRACT_DELETE_PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(CONTRACT_OUTER_PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(CONTRACT_DELETE_BENEFICIARY).balance(0L).receiverSigRequired(false),

                // Accrue a positive, payable pending reward for the staked contract.
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                getAccountBalance(CONTRACT_OUTER_PAYER).exposingBalanceTo(outerPayerBefore::set),
                getAccountBalance(CONTRACT_DELETE_BENEFICIARY).exposingBalanceTo(beneficiaryBefore::set),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> networkPendingBefore.set(rewards.pendingRewards())),
                batch,
                getTxnRecord(CONTRACT_BATCH)
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasPaidStakingRewardsCount(1)
                        .exposingTo(batchRecord::set),
                getTxnRecord(CONTRACT_DELETE_INNER)
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasPaidStakingRewardsCount(0),
                getAccountBalance(CONTRACT_OUTER_PAYER).exposingBalanceTo(outerPayerAfter::set),
                getAccountBalance(CONTRACT_DELETE_BENEFICIARY).exposingBalanceTo(beneficiaryAfter::set),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> networkPendingAfter.set(rewards.pendingRewards())),
                assertionsHold((spec, log) -> {
                    // The outer batch fee is charged.
                    Assertions.assertThat(outerPayerBefore.get() - outerPayerAfter.get())
                            .as("the outer batch fee must be charged")
                            .isPositive();
                    Assertions.assertThat(batchRecord.get().getTransactionFee()).isPositive();

                    // Record-level proof: the staked contract's pending reward is redirected to the beneficiary.
                    final var paidRewards = batchRecord.get().getPaidStakingRewardsList();
                    Assertions.assertThat(paidRewards)
                            .as("the batch record externalizes exactly one paid staking reward")
                            .hasSize(1);
                    Assertions.assertThat(paidRewards.get(0).getAccountID())
                            .as("the paid staking reward is attributed to the contract-delete beneficiary")
                            .isEqualTo(spec.registry().getAccountID(CONTRACT_DELETE_BENEFICIARY));
                    Assertions.assertThat(paidRewards.get(0).getAmount())
                            .as("the redirected reward is positive")
                            .isPositive();
                    Assertions.assertThat(beneficiaryAfter.get() - beneficiaryBefore.get())
                            .as("beneficiary receives the deleted contract's principal plus its redirected reward")
                            .isGreaterThan(paidRewards.get(0).getAmount());
                    Assertions.assertThat(networkPendingBefore.get() - networkPendingAfter.get())
                            .as("the network pending reward must decrease by the reward actually paid out")
                            .isPositive();
                    log.info(
                            "PASS: contract-delete batch SUCCESS, reward {} redirected to beneficiary, fees charged",
                            paidRewards.get(0).getAmount());
                })));
    }

    /**
     * Same fixed behavior as {@link #stakedDeleteInAtomicBatchRedirectsRewardAndChargesFees()} but via an inner
     * contract call that EVM {@code SELFDESTRUCT}s a staked contract to a beneficiary, exercising the third delete
     * path of the fix. Like {@code CryptoDelete}/{@code ContractDelete}, the self-destruct records its
     * deleted-contract to beneficiary mapping on its own inner dispatch builder, which the fix folds into the root
     * builder consulted by staking finalization. The beneficiary here is {@code msg.sender} (the inner call's
     * pre-existing payer), so this locks in the self-destruct path without depending on the separate fresh-beneficiary
     * NPE fix.
     *
     * <p>This runs under pre-EIP-6780 EVM semantics ({@code v0.46}) because only there does {@code SELFDESTRUCT}
     * delete a pre-existing contract; under the default {@code v0.70} it would merely sweep the balance and leave the
     * staked contract (and its reward) in place, so the redirect could not occur.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward",
                "contracts.evm.version"
            })
    final Stream<DynamicTest> stakedContractSelfDestructInAtomicBatchRedirectsRewardAndChargesFees() {
        final var outerPayerBefore = new AtomicLong();
        final var outerPayerAfter = new AtomicLong();
        final var networkPendingBefore = new AtomicLong();
        final var networkPendingAfter = new AtomicLong();
        final var batchRecord = new AtomicReference<TransactionRecord>();

        // The staked contract self-destructs to msg.sender — the inner call's payer, a pre-existing account —
        // recording the deleted-contract -> beneficiary mapping on its own inner dispatch builder.
        final var selfDestructInner = contractCall(SELF_DESTRUCT_CONTRACT, "destroy")
                .payingWith(SD_CALL_PAYER)
                .signedBy(SD_CALL_PAYER)
                .memo("t")
                .gas(GAS_LIMIT)
                .batchKey(BATCH_KEY)
                .via(SD_INNER);
        final var batch = atomicBatch(workThen(SD_WORK_PAYER, selfDestructInner))
                .payingWith(SD_OUTER_PAYER)
                .signedBy(SD_OUTER_PAYER, BATCH_KEY)
                .memo("b")
                .via(SD_BATCH)
                .hasKnownStatus(SUCCESS);

        return hapiTest(flattened(
                // Pre-EIP-6780 EVM semantics (v0.46): SELFDESTRUCT fully deletes a pre-existing contract,
                // which is required for a staked contract with an accrued reward to be deleted and its reward
                // redirected. Under the default v0.70 (EIP-6780) SELFDESTRUCT only deletes same-transaction
                // contracts, which can never have an accrued staking reward, so this path is unreachable there.
                overridingAllOf(java.util.Map.of(
                        "staking.startThreshold", "" + 10 * ONE_HBAR,
                        "staking.perHbarRewardRate", "6849",
                        "staking.rewardBalanceThreshold", "0",
                        "staking.requireMinStakeToReward", "false",
                        "contracts.evm.version", "v0.46")),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)),
                newKeyNamed(BATCH_KEY),
                uploadInitCode(SELF_DESTRUCT_CONTRACT),
                contractCreate(SELF_DESTRUCT_CONTRACT)
                        .stakedNodeId(0)
                        .balance(ONE_HUNDRED_HBARS)
                        .gas(CONTRACT_CREATE_GAS),
                cryptoCreate(SD_WORK_PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(SD_OUTER_PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SD_CALL_PAYER).balance(ONE_HUNDRED_HBARS).receiverSigRequired(false),

                // Accrue a positive, payable pending reward for the staked contract.
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                getAccountBalance(SD_OUTER_PAYER).exposingBalanceTo(outerPayerBefore::set),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> networkPendingBefore.set(rewards.pendingRewards())),
                batch,
                getTxnRecord(SD_BATCH)
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasPaidStakingRewardsCount(1)
                        .exposingTo(batchRecord::set),
                getTxnRecord(SD_INNER).hasPriority(recordWith().status(SUCCESS)),
                getAccountBalance(SD_OUTER_PAYER).exposingBalanceTo(outerPayerAfter::set),
                viewSingleton(
                        TokenService.NAME,
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        (NetworkStakingRewards rewards) -> networkPendingAfter.set(rewards.pendingRewards())),
                assertionsHold((spec, log) -> {
                    // The outer batch fee is charged (the self-destruct batch is not free).
                    Assertions.assertThat(outerPayerBefore.get() - outerPayerAfter.get())
                            .as("the outer batch fee must be charged")
                            .isPositive();
                    Assertions.assertThat(batchRecord.get().getTransactionFee()).isPositive();

                    // Record-level proof: the self-destructed staked contract's pending reward is redirected to the
                    // SELFDESTRUCT beneficiary (msg.sender), not dropped or thrown out of finalization.
                    final var paidRewards = batchRecord.get().getPaidStakingRewardsList();
                    Assertions.assertThat(paidRewards)
                            .as("the batch record externalizes exactly one paid staking reward")
                            .hasSize(1);
                    Assertions.assertThat(paidRewards.get(0).getAccountID())
                            .as("the paid staking reward is attributed to the self-destruct beneficiary")
                            .isEqualTo(spec.registry().getAccountID(SD_CALL_PAYER));
                    Assertions.assertThat(paidRewards.get(0).getAmount())
                            .as("the redirected reward is positive")
                            .isPositive();
                    Assertions.assertThat(networkPendingBefore.get() - networkPendingAfter.get())
                            .as("the network pending reward must decrease by the reward actually paid out")
                            .isPositive();
                    log.info(
                            "PASS: self-destruct batch SUCCESS, reward {} redirected to beneficiary, fees charged",
                            paidRewards.get(0).getAmount());
                })));
    }

    private static HapiTxnOp<?>[] workThen(final String payer, final HapiTxnOp<?> terminalOperation) {
        final var operations = new HapiTxnOp<?>[WORK_INNER_COUNT + 1];
        for (int i = 0; i < WORK_INNER_COUNT; i++) {
            operations[i] = cryptoTransfer(tinyBarsFromTo(payer, FUNDING, 1L))
                    .payingWith(payer)
                    .signedBy(payer)
                    .memo("w")
                    .batchKey(BATCH_KEY)
                    .via(workName(i));
        }
        operations[WORK_INNER_COUNT] = terminalOperation;
        return operations;
    }

    private static com.hedera.services.bdd.spec.SpecOperation[] workRecordsPresent() {
        final var queries = new com.hedera.services.bdd.spec.SpecOperation[WORK_INNER_COUNT];
        for (int i = 0; i < WORK_INNER_COUNT; i++) {
            queries[i] = getTxnRecord(workName(i)).hasPriority(recordWith().status(SUCCESS));
        }
        return queries;
    }

    private static String workName(final int index) {
        return WORK_PREFIX + "%02d".formatted(index);
    }

    private static long pendingOf(final AccountInfo info) {
        return info.getStakingInfo().getPendingReward();
    }
}
