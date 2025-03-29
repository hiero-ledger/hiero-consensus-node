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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.integration.RepeatableHip1064Tests.validateRecordFees;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(7)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableHip1064TestsDisabled {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.nodeRewardsEnabled", "false",
                "nodes.preserveMinNodeRewardBalance", "false"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> doesntPayWhenFeatureFlagDisabled() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 " + "being debited!"),
                        // We expect no reward payments in this test
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 98L, 800L, 801L)),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> preserveBalanceHasNoEffectWhenFeatureFlagDisabled() {
        final AtomicLong expectedNodeFees = new AtomicLong(0);
        return hapiTest(
                overriding("nodes.preserveMinNodeRewardBalance", "true"),
                recordStreamMustIncludeNoFailuresFrom(selectedItems(
                        (spec, records) -> Assertions.fail("Should not have any records with 801 " + "being debited!"),
                        // We expect no reward payments in this test
                        1,
                        (spec, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                                .anyMatch(aa -> aa.getAccountID().getAccountNum() == 801L && aa.getAmount() < 0L))),
                cryptoTransfer(TokenMovement.movingHbar(100000 * ONE_HBAR).between(GENESIS, NODE_REWARD)),
                // Start a new period
                waitUntilStartOfNextStakingPeriod(1),
                // Collect some node fees with a non-system payer
                cryptoCreate(CIVILIAN_PAYER),
                fileCreate("something")
                        .contents("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                        .payingWith(CIVILIAN_PAYER)
                        .via("notFree"),
                getTxnRecord("notFree")
                        .exposingTo(r -> expectedNodeFees.set(r.getTransferList().getAccountAmountsList().stream()
                                .filter(a -> a.getAccountID().getAccountNum() == 3L)
                                .findFirst()
                                .orElseThrow()
                                .getAmount())),
                // validate all network fees go to 0.0.801
                validateRecordFees("notFree", List.of(3L, 98L, 800L, 801L)),
                waitUntilStartOfNextStakingPeriod(1),
                // Trigger another round with a transaction with no fees (superuser payer)
                // so the network should pay rewards
                cryptoCreate("nobody").payingWith(GENESIS));
    }
}
