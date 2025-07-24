// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A source of transactions.
 */
@FunctionalInterface
public interface TransactionSupplier { // TODO remove

    /**
     * Returns an array of transactions. May return an empty array.
     *
     * @return an list with 0 or more transactions
     */
    @NonNull
    List<Bytes> getTransactions();
}
