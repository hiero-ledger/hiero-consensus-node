// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static java.util.Objects.requireNonNull;

import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Hash;

/**
 * A {@link VirtualHashListener} implementation used by the learner during reconnect. During reconnect,
 * the dirty leaves are sent from the teacher to the learner. Then the learner sends the leaves to a
 * {@link com.swirlds.virtualmap.internal.hash.VirtualHasher} to rehash the whole tree received from
 * the teacher. The hasher notifies this listener, which flushes the hashes to disk using {@link
 * ReconnectHashLeafFlusher} mechanism, which completely bypasses the {@link
 * com.swirlds.virtualmap.internal.cache.VirtualNodeCache} and the
 * {@link com.swirlds.virtualmap.internal.pipeline.VirtualPipeline} This is essential for performance
 * and memory reasons, since during reconnect we may need to process the entire data set, which is too
 * large to fit in memory.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
public class ReconnectHashListener<K extends VirtualKey, V extends VirtualValue> implements VirtualHashListener<K, V> {

    private final ReconnectHashLeafFlusher<K, V> flusher;

    /**
     * Create a new {@link ReconnectHashListener}.
     *
     * @param flusher Hash / leaf flusher to use to flush data to disk
     */
    public ReconnectHashListener(@NonNull final ReconnectHashLeafFlusher<K, V> flusher) {
        this.flusher = requireNonNull(flusher);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHashingStarted(long firstLeafPath, long lastLeafPath) {
        flusher.start(firstLeafPath, lastLeafPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNodeHashed(final long path, final Hash hash) {
        flusher.updateHash(path, hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onLeafHashed(final VirtualLeafRecord<K, V> leaf) {
        flusher.updateLeaf(leaf);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHashingCompleted() {
        flusher.finish();
    }
}
