// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * An event created by a node with zero or more transactions.
 * <p>
 * IMPORTANT: Although this interface is not sealed, it should only be implemented by internal classes. This
 * interface may be changed at any time, in any way, without notice or prior deprecation. Third parties should NOT
 * implement this interface.
 */
public interface Event {

    /**
     * Returns an immutable List of transactions in this event in the order provided by the creator.
     *
     * @return the transactions in this event
     */
    @NonNull
    List<? extends Transaction> getTransactions();

    /**
     * Returns the time this event was created as claimed by its creator.
     *
     * @return the created time
     */
    @NonNull
    Instant getTimeCreated();

    /**
     * Returns the creator of this event.
     *
     * @return the creator id
     */
    @NonNull
    NodeId getCreatorId();

    /**
     * Returns the birth round of this event.
     *
     * @return the birth round of the event
     */
    long getBirthRound();

    /**
     * Returns an immutable list of all the parents of this event (selfParent + otherParents).
     * This method is thread safe.
     *
     * @return a consensus transaction iterator
     */
    @NonNull
    List<EventDescriptorWrapper> getAllParents();

    /**
     * Returns the core data of the event.
     *
     * @return the core data
     */
    @NonNull
    EventCore getEventCore();

    /**
     * Returns the signature of the event.
     *
     * @return the signature
     */
    @NonNull
    Bytes getSignature();

    /**
     * Indicates if this event was read from PCES. This happens after the node
     * restarts in order to recover the latest consensus state.
     *
     * @return true if the event was read from PCES on disk, false otherwise
     */
    boolean isPcesEvent();
}
