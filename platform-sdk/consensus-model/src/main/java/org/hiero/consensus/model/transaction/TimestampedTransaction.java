// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;

/**
 * A transaction along with the timestamp when it was received by the transaction pool.
 *
 * @param transaction the transaction data
 * @param receivedTime the time when this transaction was received by the transaction pool
 */
public record TimestampedTransaction(
        @NonNull Bytes transaction,
        @NonNull Instant receivedTime) {

    /**
     * Constructor that validates inputs.
     *
     * @param transaction the transaction data
     * @param receivedTime the time when this transaction was received
     */
    public TimestampedTransaction {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(receivedTime, "receivedTime must not be null");
    }
}