// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThree;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class AtomicBatchRepeatableTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @LeakyRepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION})
    final Stream<DynamicTest> batchTxnPropagatesStakingRewards() {
        final var stakingStartThreshold = 10 * ONE_HBAR;
        final var operatorAcct = "operatorAcct";
        final var operatorKey = "operatorKey";
        final var receivesRewardsAcct = "receivesRewardsAcct";

        return hapiTest(
                overridingThree(
                        "staking.startThreshold", "" + stakingStartThreshold,
                        "staking.perHbarRewardRate", "1",
                        "staking.rewardBalanceThreshold", "0"),
                // Fund the rewards account
                cryptoTransfer(movingHbar(stakingStartThreshold).between(DEFAULT_PAYER, "800")),
                newKeyNamed(operatorKey),
                cryptoCreate(operatorAcct).key(operatorKey).balance(ONE_HUNDRED_HBARS),
                // Create an account that will receive staking rewards
                cryptoCreate(receivesRewardsAcct).balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                cryptoCreate("receiverWithSigReq").receiverSigRequired(true),
                // Accumulate some staking rewards
                waitUntilStartOfNextStakingPeriod(1),
                atomicBatch(
                                // Trigger staking rewards for the "receivesRewardsAcct" account
                                cryptoTransfer(movingHbar(1).between(operatorAcct, receivesRewardsAcct))
                                        .payingWith(operatorAcct)
                                        .batchKey(operatorKey)
                                        .via("stakingTriggered"),
                                // Intentionally fail the inner transaction to roll back the batch
                                cryptoTransfer(movingHbar(1).between(operatorAcct, "receiverWithSigReq"))
                                        .payingWith(operatorAcct)
                                        .signedBy(operatorAcct)
                                        .batchKey(operatorKey)
                                        .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(operatorAcct)
                        .signedBy(operatorKey)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify that no staking rewards were paid
                getTxnRecord("stakingTriggered").hasPaidStakingRewardsCount(0),
                getAccountBalance(receivesRewardsAcct).hasTinyBars(ONE_HUNDRED_HBARS),
                // Trigger staking again, but allow it to succeed
                waitUntilStartOfNextStakingPeriod(1),
                atomicBatch(cryptoTransfer(movingHbar(1).between(operatorAcct, receivesRewardsAcct))
                                .payingWith(operatorAcct)
                                .batchKey(operatorKey))
                        .via("batchSuccess")
                        .payingWith(operatorAcct)
                        .signedBy(operatorKey),
                // Verify staking rewards were paid
                getTxnRecord("batchSuccess").hasPaidStakingRewardsCount(1),
                getAccountBalance(receivesRewardsAcct).exposingBalanceTo(balance -> {
                    // Initial balance (100 hbars) + 1 hbar from transfer + <positive nonzero> rewards
                    Assertions.assertThat(balance).isGreaterThan(ONE_HUNDRED_HBARS + 1);
                }));
    }
}
