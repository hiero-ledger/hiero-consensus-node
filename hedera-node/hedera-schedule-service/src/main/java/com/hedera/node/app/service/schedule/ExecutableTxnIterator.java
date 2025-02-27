// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import java.util.Iterator;

/**
 * An iterator over executable transactions that can also purge state up to the next known executable transaction.
 */
public interface ExecutableTxnIterator extends Iterator<ExecutableTxn<? extends StreamBuilder>> {
    /**
     * Purges any expired state up to the point of the next known executable transaction.
     * @return whether any state was purged
     * @throws IllegalStateException if {@link Iterator#hasNext()} was never called
     */
    boolean purgeUntilNext();
}
