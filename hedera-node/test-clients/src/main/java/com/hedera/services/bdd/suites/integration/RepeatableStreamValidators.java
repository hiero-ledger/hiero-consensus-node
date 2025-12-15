package com.hedera.services.bdd.suites.integration;

import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RepeatableStreamValidators {
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
}
