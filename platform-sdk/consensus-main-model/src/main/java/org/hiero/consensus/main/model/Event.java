// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main.model;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * An event created by a node with zero or more transactions.
 * <p>
 * IMPORTANT: Although this interface is not sealed, it should only be implemented by internal classes. This
 * interface may be changed at any time, in any way, without notice or prior deprecation. Third parties should NOT
 * implement this interface.
 */
public interface Event {

    /**
     * Returns a List application events in this transaction.
     *
     * @return a transaction iterator
     */
    @NonNull
    List<? extends Transaction> getTransactions();

    /**
     * Returns the time this event was created as claimed by its creator.
     *
     * @return the created time
     */
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
     * @see EventDescriptor#birthRound()
     * @return the birth round of the event
     */
    long getBirthRound();

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

    /**
     * Wait until all transactions have been prehandled for this event.
     */
    void awaitPrehandleCompletion();

    /**
     * Signal that all transactions have been prehandled for this event.
     */
    void signalPrehandleCompletion();
}
