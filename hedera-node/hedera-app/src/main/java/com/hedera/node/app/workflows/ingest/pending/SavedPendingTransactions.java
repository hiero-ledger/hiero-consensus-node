// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * User transactions drained from the transaction pool at a freeze.
 *
 * @param freezeRound the freeze round
 * @param transactions the serialized {@code SignedTransaction} bytes, oldest first
 */
public record SavedPendingTransactions(
        long freezeRound, @NonNull List<Bytes> transactions) {
    public SavedPendingTransactions {
        transactions = List.copyOf(requireNonNull(transactions));
    }
}
