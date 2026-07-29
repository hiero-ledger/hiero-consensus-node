// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Iterator;
import org.hiero.base.crypto.Hash;

/**
 * An event that has reached consensus.
 * <p>
 * IMPORTANT: Although this interface is not sealed, it should only be implemented by internal classes. This
 * interface may be changed at any time, in any way, without notice or prior deprecation. Third parties should NOT
 * implement this interface.
 */
public interface ConsensusEvent extends Event {

    /**
     * Returns an iterator over the application events in this transaction, which have all reached consensus. Each
     * invocation returns a new iterator over the same transactions. This method is thread safe.
     *
     * @return a consensus transaction iterator
     */
    @NonNull
    Iterator<ConsensusTransaction> consensusTransactionIterator();

    /**
     * Returns the hash of this event.
     *
     * @return the hash of the metadata of this event
     */
    @NonNull
    Hash getHash();

    /**
     * Returns an iterator over all the parents of this event (selfParent + otherParents). Each
     * invocation returns a new iterator over the same all parents of this event. This method is thread safe.
     *
     * @return a consensus transaction iterator
     */
    @NonNull
    Iterator<EventDescriptorWrapper> allParentsIterator();

    /**
     * Returns the consensus order of the consensus item, starting at zero. Smaller values occur before higher numbers.
     *
     * @return the consensus order sequence number
     */
    long getConsensusOrder();

    /**
     * Returns the community's consensus timestamp for this item.
     *
     * @return the consensus timestamp
     */
    Instant getConsensusTimestamp();
}
