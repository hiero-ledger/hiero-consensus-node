// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * A hashgraph transaction that consists of an array of bytes and a list of immutable
 * {@code com.swirlds.common.crypto.TransactionSignature} objects. The list of signatures features controlled mutability
 * with a thread-safe and atomic implementation. The transaction internally uses a {@link ReadWriteLock} to provide
 * atomic reads and writes to the underlying list of signatures.
 */
public sealed interface Transaction permits ConsensusTransaction {

    /**
     * A convenience method for retrieving the application transaction {@link Bytes} object.
     *
     * @return the application transaction Bytes
     */
    Bytes getApplicationTransaction();

    /**
     * Get the size of the transaction
     *
     * @return the size of the transaction in the unit of byte
     */
    long getSize();

    /**
     * Returns the custom metadata object set via {@link #setMetadata(Object)}.
     *
     * @param <T>
     * 		the type of metadata object to return
     * @return the custom metadata object, or {@code null} if none was set
     * @throws ClassCastException
     * 		if the type of object supplied to {@link #setMetadata(Object)} is not compatible with {@code T}
     */
    <T> T getMetadata();

    /**
     * Attaches a custom object to this transaction meant to store metadata. This object is not serialized
     * and is kept in memory. It must be recalculated by the application after a restart.
     *
     * @param <T>
     * 		the object to attach
     */
    <T> void setMetadata(T metadata);
}
