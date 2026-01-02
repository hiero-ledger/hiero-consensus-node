// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1259;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountAmount;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public class ValidationUtils {
    /**
     * Validator for legacy node rewards (when HIP-1259 is disabled).
     */
    public static VisibleItemsValidator nodeRewardsValidator(@NonNull final LongSupplier nodeRewardBalance) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments found");
            assertEquals(1, items.size());
            final var payment = items.getFirst();
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            // Node reward account should be debited
            final long nodeRewardDebit =
                    bodyAdjustments.get(spec.startupProperties().getLong("accounts.nodeRewardAccount"));
            assertTrue(nodeRewardDebit < 0, "Node reward account should be debited");
        };
    }

    public static SpecOperation validateRecordContains(final String record, List<Long> expectedFeeAccounts) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            var txnRecord = getTxnRecord(record);
            allRunFor(spec, txnRecord);
            var response = txnRecord.getResponseRecord();
            assertEquals(
                    1,
                    response.getTransferList().getAccountAmountsList().stream()
                            .filter(aa -> aa.getAmount() < 0)
                            .count());
            assertTrue(response.getTransferList().getAccountAmountsList().stream()
                    .filter(aa -> aa.getAmount() > 0)
                    .map(aa -> aa.getAccountID().getAccountNum())
                    .sorted()
                    .toList()
                    .containsAll(expectedFeeAccounts));
        });
    }

    public static SpecOperation validateRecordNotContains(final String record, List<Long> expectedFeeAccounts) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            var txnRecord = getTxnRecord(record);
            allRunFor(spec, txnRecord);
            var response = txnRecord.getResponseRecord();
            for (final var expectedFeeAccount : expectedFeeAccounts) {
                assertFalse(response.getTransferList().getAccountAmountsList().stream()
                        .anyMatch(aa -> aa.getAccountID().getAccountNum() == expectedFeeAccount));
            }
        });
    }

    public static boolean isNodeRewardOrFeeDistribution(
            final RecordStreamItem item, final AtomicReference<Instant> startConsensusTime) {
        return item.getRecord().getTransferList().getAccountAmountsList().stream()
                .anyMatch(aa -> {
                    final var accountNum = aa.getAccountID().getAccountNum();
                    final var amount = aa.getAmount();
                    final var isAfter = asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                            .minusSeconds(60)
                            .isAfter(startConsensusTime.get());
                    return ((accountNum == 801L || accountNum == 802L) && amount < 0L) && isAfter;
                });
    }

    public static boolean hasFeeDistribution(
            final RecordStreamItem item, final AtomicReference<Instant> startConsensusTime) {
        return item.getRecord().getTransferList().getAccountAmountsList().stream()
                .anyMatch(aa -> {
                    final var accountNum = aa.getAccountID().getAccountNum();
                    final var amount = aa.getAmount();
                    final var isAfter = asInstant(toPbj(item.getRecord().getConsensusTimestamp()))
                            .minusSeconds(60)
                            .isAfter(startConsensusTime.get());
                    return ((accountNum == 802L) && amount < 0L) && isAfter;
                });
    }

    /**
     * Validator for fee distribution synthetic transaction.
     */
    public static VisibleItemsValidator feeDistributionValidator(
            int recordNumber, List<Long> creditAccounts, @Nullable final LongSupplier expectedFees) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No fee distribution found");
            assertNotNull(items.get(recordNumber - 1), "No fee distribution found");
            final var payment = items.get(recordNumber - 1);
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            for (Long account : creditAccounts) {
                assertTrue(
                        bodyAdjustments.containsKey(account) && bodyAdjustments.get(account) > 0,
                        "Credit account should be credited");
            }
            if (expectedFees != null) {
                assertEquals((long) bodyAdjustments.get(3L), expectedFees.getAsLong(), "Node account fee should match");
            }
        };
    }

    public static VisibleItemsValidator feeDistributionValidator(int recordNumber, List<Long> creditAccounts) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No fee distribution found");
            assertNotNull(items.get(recordNumber - 1), "No fee distribution found");
            final var payment = items.get(recordNumber - 1);
            assertEquals(CryptoTransfer, payment.function());
            final var op = payment.body().getCryptoTransfer();
            final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                    .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
            for (Long account : creditAccounts) {
                assertTrue(
                        bodyAdjustments.containsKey(account) && bodyAdjustments.get(account) > 0,
                        "Credit account should be credited");
            }
        };
    }

    /**
     * Validator for node rewards with fee collection enabled.
     * Validates that:
     * 1. Fee distributions happen first (0.0.802 is debited, node accounts and system accounts are credited)
     * 2. Node rewards are distributed last (0.0.801 is debited, node accounts are credited)
     */
    public static VisibleItemsValidator nodeRewardsWithFeeCollectionValidator(
            @NonNull final LongSupplier initialNodeBalance,
            @NonNull final LongSupplier nodeAccountBalanceAfterDistribution) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No reward payments or fee distributions found");
            assertTrue(items.size() >= 2, "Expected at least 2 records (fee distribution + node rewards)");

            // Always fee distributions happen first and then node rewards
            // validate the order of the transactions
            boolean foundFeeDistribution = false;
            boolean foundNodeReward = false;
            int feeDistributionIndex = -1;
            int nodeRewardIndex = -1;
            long nodeFees = 0L;

            for (int i = 0; i < items.size(); i++) {
                final var payment = items.get(i);
                assertEquals(CryptoTransfer, payment.function());
                final var op = payment.body().getCryptoTransfer();
                final Map<Long, Long> bodyAdjustments = op.getTransfers().getAccountAmountsList().stream()
                        .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));

                // Check if this is a fee distribution (0.0.802 is debited)
                if (bodyAdjustments.containsKey(802L) && bodyAdjustments.get(802L) < 0) {
                    foundFeeDistribution = true;
                    if (feeDistributionIndex == -1) {
                        feeDistributionIndex = i;
                    }
                    // Validate fee distribution: 0.0.802 debited, node accounts (3) and system accounts credited
                    assertTrue(
                            bodyAdjustments.containsKey(3L) && bodyAdjustments.get(3L) > 0,
                            "Node account 0.0.3 should be credited in fee distribution");
                    assertTrue(
                            bodyAdjustments.containsKey(802L) && bodyAdjustments.get(802L) < 0,
                            "System account 0.0.802 should be debited in fee distribution");
                    if (bodyAdjustments.containsKey(3L)) {
                        nodeFees += bodyAdjustments.get(3L);
                    }
                }

                // Check if this is a node reward distribution (0.0.801 is debited)
                if (bodyAdjustments.containsKey(801L) && bodyAdjustments.get(801L) < 0) {
                    foundNodeReward = true;
                    nodeRewardIndex = i;
                    // Validate node reward: 0.0.801 debited, node accounts credited
                    final long nodeRewardDebit = bodyAdjustments.get(801L);
                    assertTrue(nodeRewardDebit < 0, "Node reward account 0.0.801 should be debited");

                    // Sum of credits to node accounts should equal the debit from 0.0.801
                    final long totalCredits = bodyAdjustments.entrySet().stream()
                            .filter(e -> e.getKey() != 801L && e.getValue() > 0)
                            .mapToLong(Map.Entry::getValue)
                            .sum();
                    assertEquals(
                            -nodeRewardDebit,
                            totalCredits,
                            "Total credits to node accounts should equal debit from 0.0.801");
                }
            }

            assertTrue(foundFeeDistribution, "Should have at least one fee distribution transaction");
            assertTrue(foundNodeReward, "Should have at least one node reward transaction");
            assertTrue(feeDistributionIndex < nodeRewardIndex, "Fee distribution should happen before node rewards");
            assertEquals(
                    initialNodeBalance.getAsLong() + nodeFees,
                    nodeAccountBalanceAfterDistribution.getAsLong(),
                    "Node account balance should match");
        };
    }
}
