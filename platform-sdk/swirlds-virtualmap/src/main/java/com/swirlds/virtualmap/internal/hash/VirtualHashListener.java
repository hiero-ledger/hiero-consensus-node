// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;

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
     * Called after each hash chunk is hashed. This is called between
     * {@link #onHashingStarted(long, long)} and {@link #onHashingCompleted()}.
     *
     * @param chunk Non-null hash chunk
     */
    default void onHashChunkHashed(@NonNull final VirtualHashChunk chunk) {}

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
