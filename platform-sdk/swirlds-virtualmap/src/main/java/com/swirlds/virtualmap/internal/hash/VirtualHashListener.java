// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import org.hiero.consensus.model.crypto.Hash;

/**
 * Listens to various events that occur during the hashing process.
 */
public interface VirtualHashListener {
    /**
     * Called when starting a new fresh hash operation.
     */
    default void onHashingStarted() {}

    /**
     * Called after each node is hashed, internal or leaf. This is called between
     * {@link #onHashingStarted()} and {@link #onHashingCompleted()}.
     *
     * @param path
     * 		Node path
     * @param hash
     * 		A non-null node hash
     */
    default void onNodeHashed(final long path, final Hash hash) {}

    /**
     * Called after each leaf node on a rank is hashed. This is called between
     * {@link #onHashingStarted()} and {@link #onHashingCompleted()}.
     *
     * @param leaf
     * 		A non-null leaf record representing the hashed leaf.
     */
    default void onLeafHashed(VirtualLeafBytes<?> leaf) {}

    /**
     * Called when all hashing has completed.
     */
    default void onHashingCompleted() {}
}
