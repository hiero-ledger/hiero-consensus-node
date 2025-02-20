// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import com.swirlds.platform.system.ReachedConsensus;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An event that has reached consensus.
 * <p>
 * IMPORTANT: Although this interface is not sealed, it should only be implemented by internal classes. This
 * interface may be changed at any time, in any way, without notice or prior deprecation. Third parties should NOT
 * implement this interface.
 */
public interface ConsensusEvent extends Event, ReachedConsensus {

    /**
     * Returns an iterator over the application events in this transaction, which have all reached consensus. Each
     * invocation returns a new iterator over the same transactions. This method is thread safe.
     *
     * @return a consensus transaction iterator
     */
    @NonNull
    Iterator<ConsensusTransaction> consensusTransactionIterator();
}
