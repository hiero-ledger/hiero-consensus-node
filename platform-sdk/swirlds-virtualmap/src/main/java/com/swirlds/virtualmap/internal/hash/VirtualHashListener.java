// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import org.hiero.base.crypto.Hash;

/**
 * Listens to various events that occur during the hashing process.
 */
public interface VirtualHashListener {
    /**
     * Called when starting a new fresh hash operation.
     *
     * @param firstLeafPath
     *      The first leaf path in the virtual tree
     * @param lastLeafPath
     *      The last leaf path in the virtual tree
     */
    default void onHashingStarted(final long firstLeafPath, final long lastLeafPath) {}

    /**
     * Called after each node is hashed, internal or leaf. This is called between
     * {@link #onHashingStarted(long, long)} and {@link #onHashingCompleted()}.
     *
     * @param path
     * 		Node path
     * @param hash
     * 		A non-null node hash
     */
    default void onNodeHashed(final long path, final Hash hash) {}

    /**
     * Called after each leaf node on a rank is hashed. This is called between
     * {@link #onHashingStarted(long, long)} and {@link #onHashingCompleted()}.
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
