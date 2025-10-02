// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Provides transactions for new events being created.
 */
@FunctionalInterface
public interface EventTransactionSupplier {

    /**
     * Returns a list of transactions that will be part of a newly created event. May return an empty list.
     *
     * @return a list with 0 or more transactions
     */
    @NonNull
    List<Bytes> getTransactionsForEvent();
}
