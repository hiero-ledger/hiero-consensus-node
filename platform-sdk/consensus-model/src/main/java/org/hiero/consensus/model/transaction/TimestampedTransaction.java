// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;

/**
 * A transaction along with the timestamp when it was received.
 *
 * @param transaction the transaction data
 * @param receivedTime the time when this transaction was received
 * @param priority whether this transaction was submitted with priority, which puts it into an event ahead of all
 *                 waiting transactions without priority
 */
public record TimestampedTransaction(
        @NonNull Bytes transaction, @NonNull Instant receivedTime, boolean priority) {

    /**
     * Constructor that validates inputs.
     *
     * @param transaction the transaction data
     * @param receivedTime the time when this transaction was received
     * @param priority whether this transaction was submitted with priority
     */
    public TimestampedTransaction {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(receivedTime, "receivedTime must not be null");
    }

    /**
     * Constructor that defaults priority to false.
     *
     * @param transaction the transaction data
     * @param receivedTime the time when this transaction was received
     */
    public TimestampedTransaction(@NonNull final Bytes transaction, @NonNull final Instant receivedTime) {
        this(transaction, receivedTime, false);
    }
}
