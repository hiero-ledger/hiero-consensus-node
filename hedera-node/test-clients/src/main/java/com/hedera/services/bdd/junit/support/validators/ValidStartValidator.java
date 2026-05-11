// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static com.hedera.services.bdd.junit.support.validators.BalanceReconciliationValidator.streamOfItemsFrom;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.List;

/** Validates every record's valid start is at or before its consensus time. */
public class ValidStartValidator implements RecordStreamValidator {
    public ValidStartValidator() {}

    @Override
    public void validateRecordsAndSidecars(final List<RecordWithSidecars> recordsWithSidecars) {
        streamOfItemsFrom(recordsWithSidecars).map(item -> item.getRecord()).forEach(this::assertValidStartAtOrBefore);
    }

    private void assertValidStartAtOrBefore(final TransactionRecord record) {
        assertTrue(record.hasTransactionID(), () -> "Record has no transaction ID: " + record);
        final var transactionId = record.getTransactionID();
        assertTrue(transactionId.hasTransactionValidStart(), () -> "TransactionID has no valid start: " + record);
        assertTrue(record.hasConsensusTimestamp(), () -> "Record has no consensus timestamp: " + record);
        final var validStart = transactionId.getTransactionValidStart();
        final var consensusTime = record.getConsensusTimestamp();
        assertTrue(
                isAtOrBefore(validStart, consensusTime),
                () -> "Transaction valid start "
                        + validStart
                        + " is after assigned consensus time "
                        + consensusTime
                        + " for "
                        + transactionId);
    }

    private static boolean isAtOrBefore(final Timestamp validStart, final Timestamp consensusTime) {
        return validStart.getSeconds() < consensusTime.getSeconds()
                || (validStart.getSeconds() == consensusTime.getSeconds()
                        && validStart.getNanos() <= consensusTime.getNanos());
    }
}
