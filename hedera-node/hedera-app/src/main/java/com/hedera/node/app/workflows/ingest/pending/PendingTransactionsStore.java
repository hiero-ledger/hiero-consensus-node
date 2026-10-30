// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Optional;

/**
 * Keeps user transactions drained at a freeze until the node starts again.
 */
public interface PendingTransactionsStore {
    /**
     * Replaces any saved transactions with the given ones.
     *
     * @param saved the transactions to save
     * @throws IOException if they cannot be saved
     */
    void save(@NonNull SavedPendingTransactions saved) throws IOException;

    /**
     * Returns the saved transactions, or empty if there are none or they cannot be read.
     *
     * @return the saved transactions, if any
     */
    @NonNull
    Optional<SavedPendingTransactions> load();

    /**
     * Deletes any saved transactions.
     *
     * @return true if no saved file remains
     */
    boolean delete();
}
