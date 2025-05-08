// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static com.hedera.services.bdd.junit.TestBase.concurrentExecutionOf;

import com.hedera.services.bdd.junit.TestBase;
import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hedera.services.bdd.junit.support.validators.utils.AccountClassifier;
import com.hedera.services.bdd.suites.records.BalanceValidation;
import com.hedera.services.stream.proto.RecordStreamItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * This validator "reconciles" the hbar balances of all accounts and contract between the record
 * stream and the network state, comparing two sources of truth at the end of the CI test run:
 *
 * <ol>
 *   <li>The balances implied by the {@code TransferList} adjustments in the record stream.
 *   <li>The balances returned by {@code getAccountBalance} and {@code getContractInfo} queries.
 * </ol>
 *
 * <p>It uses the {@link BalanceValidation} suite to perform the queries.
 */
public class BalanceReconciliationValidator implements RecordStreamValidator {
    private final Map<Long, Long> expectedBalances = new HashMap<>();

    private final AccountClassifier accountClassifier = new AccountClassifier();

    @Override
    @SuppressWarnings("java:S106")
    public void validateRecordsAndSidecars(final List<RecordWithSidecars> recordsWithSidecars) {
        getExpectedBalanceFrom(recordsWithSidecars);
        System.out.println("Expected balances: " + expectedBalances);

        final var validationSpecs = TestBase.extractContextualizedSpecsFrom(
                List.of(() -> new BalanceValidation(expectedBalances, accountClassifier)),
                TestBase::contextualizedSpecsFromConcurrent);
        concurrentExecutionOf(validationSpecs);
    }

    private void getExpectedBalanceFrom(final List<RecordWithSidecars> recordsWithSidecars) {
        for (final var recordWithSidecars : recordsWithSidecars) {
            final var items = recordWithSidecars.recordFile().getRecordStreamItemsList();
            for (final var item : items) {
                accountClassifier.incorporate(item);
                final var grpcRecord = item.getRecord();
                grpcRecord.getTransferList().getAccountAmountsList().forEach(aa -> {
                    final var accountNum = aa.getAccountID().getAccountNum();
                    final var amount = aa.getAmount();
                    expectedBalances.merge(accountNum, amount, Long::sum);
                });
            }

            for (final var entry : expectedBalances.entrySet()) {
                if (entry.getValue() < 0) {
                    throw new IllegalStateException(
                            "Negative balance for account " + entry.getKey() + " with value " + entry.getValue());
                }
            }
        }
    }

    public static Stream<RecordStreamItem> streamOfItemsFrom(final List<RecordWithSidecars> recordsWithSidecars) {
        return recordsWithSidecars.stream()
                .flatMap(recordWithSidecars -> recordWithSidecars.recordFile().getRecordStreamItemsList().stream());
    }
}
