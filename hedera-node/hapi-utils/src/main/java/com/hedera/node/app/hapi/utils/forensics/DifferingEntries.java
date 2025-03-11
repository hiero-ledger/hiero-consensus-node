// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static java.util.Objects.requireNonNull;

import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Wraps up to two record stream entries that e.g. had different
 * {@link com.hederahashgraph.api.proto.java.TransactionRecord}s;
 * or, in an even more extreme divergence, did not even have the
 * same {@link Transaction} or consensus time.
 *
 * <p>If only one entry is non-null, it implies the other stream
 * did not have a corresponding entry; e.g., the first stream had
 * 10 entries and the second only 9 entries.
 *
 * @param firstEntry the entry from the first stream that differed, if any
 * @param secondEntry the entry from the second stream that differed, if any
 * @param summary a more concise summary of the divergence, if available
 */
public record DifferingEntries(
        @Nullable RecordStreamEntry firstEntry, @Nullable RecordStreamEntry secondEntry, @Nullable String summary) {

    public enum FirstEncounteredDifference {
        FIRST_IS_MISSING,
        SECOND_IS_MISSING,
        CONSENSUS_TIME_MISMATCH,
        TRANSACTION_MISMATCH,
        TRANSACTION_RECORD_MISMATCH,
    }

    public FirstEncounteredDifference firstEncounteredDifference() {
        if (firstEntry == null) {
            return FirstEncounteredDifference.FIRST_IS_MISSING;
        } else if (secondEntry == null) {
            return FirstEncounteredDifference.SECOND_IS_MISSING;
        } else if (!firstEntry.consensusTime().equals(secondEntry.consensusTime())) {
            return FirstEncounteredDifference.CONSENSUS_TIME_MISMATCH;
        } else if (!firstEntry.parts().wrapper().equals(secondEntry.parts().wrapper())) {
            return FirstEncounteredDifference.TRANSACTION_MISMATCH;
        } else {
            return FirstEncounteredDifference.TRANSACTION_RECORD_MISMATCH;
        }
    }

    public String involvedFunctions() {
        if (firstEntry == null) {
            return requireNonNull(secondEntry).parts().function().name();
        } else if (secondEntry == null) {
            return requireNonNull(firstEntry).parts().function().name();
        } else {
            final var firstFunction = firstEntry.parts().function();
            final var secondFunction = secondEntry.parts().function();
            return (firstFunction == secondFunction)
                    ? firstFunction.name()
                    : String.format("%s ≠ %s", firstFunction, secondFunction);
        }
    }
}
