// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.state.BinaryStateUtils.createVirtualMapKeyBytesForKv;
import static com.swirlds.state.BinaryStateUtils.createVirtualMapKeyBytesForQueue;
import static com.swirlds.state.BinaryStateUtils.getVirtualMapKeyForSingleton;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.BinaryState;
import com.swirlds.state.merkle.queue.QueueState;
import com.swirlds.state.merkle.queue.QueueStateCodec;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.exceptions.ReferenceCountException;

public class VirtualMapBinaryState implements BinaryState {

    private static final Logger logger = LogManager.getLogger(VirtualMapBinaryState.class);
    private static final String VM_LABEL = "state";

    private final VirtualMap virtualMap;

    /**
     * Metrics for the snapshot creation process
     */
    private MerkleRootSnapshotMetrics snapshotMetrics = new MerkleRootSnapshotMetrics();

    public VirtualMapBinaryState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        requireNonNull(configuration, "configuration must not be null");
        requireNonNull(metrics, "metrics must not be null");
        final MerkleDbDataSourceBuilder dsBuilder;
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        final var tableConfig = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                // FUTURE WORK: drop StateDefinition.maxKeysHint and load VM size from VirtualMapConfig.size instead
                merkleDbConfig.maxNumOfKeys(),
                merkleDbConfig.hashesRamToDiskThreshold());
        dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, configuration);

        this.virtualMap = new VirtualMap(VM_LABEL, dsBuilder, configuration);
        this.virtualMap.registerMetrics(metrics);
    }

    protected VirtualMapBinaryState(@NonNull final VirtualMapBinaryState virtualMapBinaryState) {
        requireNonNull(virtualMapBinaryState, "virtualMapBinaryState must not be null");
        this.virtualMap = virtualMapBinaryState.virtualMap.copy();
        this.snapshotMetrics = virtualMapBinaryState.snapshotMetrics;
    }

    /**
     * Initializes a {@link VirtualMapBinaryState} with the specified {@link VirtualMap}.
     *
     * @param virtualMap the virtual map with pre-registered metrics
     */
    public VirtualMapBinaryState(@NonNull final VirtualMap virtualMap) {
        requireNonNull(virtualMap);
        this.virtualMap = virtualMap;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putSingleton(final int stateId, @Nullable final Bytes value) {
        virtualMap.putBytes(getVirtualMapKeyForSingleton(stateId), value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> void putSingleton(final int id, final @NonNull Codec<T> codec, @NonNull final T value) {
        final Bytes key = getVirtualMapKeyForSingleton(id);
        final Bytes valueBytes = codec.toBytes(value);
        if (valueBytes == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        virtualMap.putBytes(key, valueBytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes getSingleton(final int stateId) {
        return virtualMap.getBytes(getVirtualMapKeyForSingleton(stateId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T getSingleton(final int id, @NonNull final Codec<T> codec) {
        return virtualMap.get(getVirtualMapKeyForSingleton(id), codec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes removeSingleton(final int id) {
        return virtualMap.remove(getVirtualMapKeyForSingleton(id));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T removeSingleton(final int id, @NonNull final Codec<T> codec) {
        return virtualMap.remove(getVirtualMapKeyForSingleton(id), codec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putKeyValuePair(final int id, @NonNull final Bytes key, @NonNull final Bytes value) {
        virtualMap.putBytes(createVirtualMapKeyBytesForKv(id, key), value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <K, V> void putKeyValuePair(
            final int id,
            @NonNull final Codec<K> keyCodec,
            @NonNull final K key,
            @Nullable final Codec<V> valueCodec,
            @Nullable final V value) {
        virtualMap.put(createVirtualMapKeyBytesForKv(id, keyCodec.toBytes(key)), value, valueCodec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes removeKeyValuePair(final int id, @NonNull final Bytes key) {
        return virtualMap.remove(createVirtualMapKeyBytesForKv(id, key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <K, V> V removeKeyValuePair(
            final int id, @NonNull final Codec<K> keyCodec, @NonNull final K key, @NonNull final Codec<V> valueCodec) {
        return virtualMap.remove(createVirtualMapKeyBytesForKv(id, keyCodec.toBytes(key)), valueCodec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes getValueByKey(final int id, @NonNull final Bytes key) {
        return virtualMap.getBytes(createVirtualMapKeyBytesForKv(id, key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <K, V> V getValueByKey(
            final int id, @NonNull final Codec<K> keyCodec, @NonNull final K key, @NonNull final Codec<V> valueCodec) {
        return virtualMap.get(createVirtualMapKeyBytesForKv(id, keyCodec.toBytes(key)), valueCodec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void queueAdd(final int id, @Nullable final Bytes value) {
        QueueState state = getQueueState(id);
        if (state == null) {
            // Adding to this Queue State first time - initialize QueueState.
            state = new QueueState();
        }
        virtualMap.putBytes(createVirtualMapKeyBytesForQueue(id, state.getTailAndIncrement()), value);
        updateQueueState(id, state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> void queueAdd(final int id, @Nullable final Codec<T> codec, @Nullable final T value) {
        QueueState state = getQueueState(id);
        if (state == null) {
            // Adding to this Queue State first time - initialize QueueState.
            state = new QueueState();
        }
        virtualMap.put(createVirtualMapKeyBytesForQueue(id, state.getTailAndIncrement()), value, codec);
        updateQueueState(id, state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes queuePoll(final int id) {
        final QueueState state = requireNonNull(getQueueState(id));
        final Bytes removedValue = virtualMap.remove(createVirtualMapKeyBytesForQueue(id, state.getHeadAndIncrement()));
        updateQueueState(id, state);
        return removedValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T queuePoll(final int id, @NonNull final Codec<T> codec) {
        final QueueState state = requireNonNull(getQueueState(id));
        final T removedValue =
                virtualMap.remove(createVirtualMapKeyBytesForQueue(id, state.getHeadAndIncrement()), codec);
        updateQueueState(id, state);
        return removedValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes queuePeek(final int id) {
        final QueueState state = getQueueState(id);
        if (state == null) {
            return null;
        }
        return virtualMap.getBytes(createVirtualMapKeyBytesForQueue(id, state.getHead()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T queuePeek(final int id, @NonNull final Codec<T> codec) {
        final QueueState state = getQueueState(id);
        if (state == null) {
            return null;
        }
        return getQueueElementAt(id, state.getHead(), codec);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public <T> Iterator<T> createQueueIterator(final int id, @NonNull final Codec<T> codec) {
        return new QueueIterator<>(id, codec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHash() {
        return virtualMap.getHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHashed() {
        return virtualMap.isHashed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualMapBinaryState copy() {
        return new VirtualMapBinaryState(this);
    }

    /**
     * Returns the size of the state
     */
    public long size() {
        return virtualMap.size();
    }

    /**
     * Determines if an object is immutable or not.
     *
     * @return Whether is immutable or not
     */
    public boolean isImmutable() {
        return virtualMap.isImmutable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean release() {
        return virtualMap.release();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reserve() {
        virtualMap.reserve();
    }

    /**
     * Determines if an object has been fully released and garbage collected.
     *
     * @return Whether is has been released or not
     */
    public boolean isDestroyed() {
        return virtualMap.isDestroyed();
    }

    /**
     * Throws an exception if {@link #isDestroyed()}} returns {@code true}
     *
     * @throws ReferenceCountException
     * 		if this object is destroyed
     */
    public void throwIfDestroyed() {
        throwIfDestroyed("This operation is not permitted on a destroyed object.");
    }

    /**
     * Throws an exception if {@link #isDestroyed()}} returns {@code true}
     *
     * @param errorMessage
     * 		an error message for the exception
     * @throws ReferenceCountException
     * 		if this object is destroyed
     */
    public void throwIfDestroyed(final String errorMessage) {
        if (this.isDestroyed()) {
            throw new ReferenceCountException(errorMessage);
        }
    }

    /**
     * Determines if an object is mutable or not.
     *
     * @return Whether the object is immutable or not
     */
    public boolean isMutable() {
        return !isImmutable();
    }

    /**
     * @throws MutabilityException if {@link #isImmutable()}} returns {@code true}
     */
    public void throwIfImmutable() {
        throwIfImmutable("This operation is not permitted on an immutable object.");
    }

    /**
     * @param errorMessage an error message for the exception
     * @throws MutabilityException if {@link #isImmutable()}} returns {@code true}
     */
    public void throwIfImmutable(@NonNull final String errorMessage) {
        if (isImmutable()) {
            throw new MutabilityException(errorMessage);
        }
    }

    /**
     * @throws MutabilityException if {@link #isMutable()} returns {@code true}
     */
    public void throwIfMutable() {
        throwIfMutable("This operation is not permitted on a mutable object.");
    }

    /**
     * @param errorMessage an error message for the exception
     * @throws MutabilityException if {@link #isMutable()}} returns {@code true}
     */
    public void throwIfMutable(@NonNull final String errorMessage) {
        if (isMutable()) {
            throw new MutabilityException(errorMessage);
        }
    }

    /**
     * Create a pre-ordered depth-first iterator for the virtual map.
     *
     * @return a configurable iterator
     */
    public MerkleIterator<MerkleNode> treeIterator() {
        return virtualMap.treeIterator();
    }

    /**
     * To be called ONLY at node shutdown. Attempts to gracefully close the Virtual Map.
     */
    public void close() {
        logger.info("Closing VirtualMapBinaryState");
        try {
            virtualMap.getDataSource().close();
        } catch (IOException e) {
            logger.warn("Unable to close data source for the Virtual Map", e);
        }
    }

    /**
     * Makes sure that the specified key is loaded into the VirtualMap's cache.
     * @param key the key to warm up
     */
    public void warm(Bytes key) {
        virtualMap.warm(key);
    }

    protected VirtualMap getVirtualMap() {
        return virtualMap;
    }

    private QueueState getQueueState(final int id) {
        final Bytes vmKey = getVirtualMapKeyForSingleton(id);
        QueueState state = virtualMap.get(vmKey, QueueStateCodec.INSTANCE);
        if (state == null) {
            return null;
        }
        // FUTURE WORK: optimize performance here, see https://github.com/hiero-ledger/hiero-consensus-node/issues/19670
        return new QueueState(state.getHead(), state.getTail());
    }

    private void updateQueueState(int id, @NonNull final QueueState state) {
        final Bytes vmKey = getVirtualMapKeyForSingleton(id);
        virtualMap.put(vmKey, state, QueueStateCodec.INSTANCE);
    }

    private <E> E getQueueElementAt(final int queueId, final long index, @NonNull final Codec<E> codec) {
        return virtualMap.get(createVirtualMapKeyBytesForQueue(queueId, index), codec);
    }

    /**
     * Utility class for iterating over queue elements within a specific range.
     */
    private class QueueIterator<E> implements Iterator<E> {

        private final int queueId;
        private final Codec<E> codec;
        /**
         * The starting position of the iteration (inclusive).
         */
        private final long start;

        /**
         * The ending position of the iteration (exclusive).
         */
        private final long limit;

        /**
         * The current position of the iterator, where {@code start <= current < limit}.
         */
        private long current;

        /**
         * Creates a new iterator for the specified range.
         *
         */
        public QueueIterator(final int queueId, final Codec<E> codec) {
            QueueState queueState = getQueueState(queueId);
            if (queueState == null) {
                start = 0;
                limit = 0;
            } else {
                start = queueState.getHead();
                limit = queueState.getTail();
            }
            this.queueId = queueId;
            this.codec = codec;
            reset();
        }

        /**
         * Checks if there are more elements to iterate over.
         *
         * @return {@code true} if there are more elements, {@code false} otherwise.
         */
        @Override
        public boolean hasNext() {
            return current < limit;
        }

        /**
         * Returns the next element in the iteration.
         *
         * @return The next element in the queue.
         * @throws NoSuchElementException          If no more elements are available.
         * @throws ConcurrentModificationException If the queue was modified during iteration.
         */
        @Override
        public E next() {
            if (current == limit) {
                throw new NoSuchElementException();
            }
            try {
                return getQueueElementAt(queueId, current++, codec);
            } catch (final IllegalStateException e) {
                throw new ConcurrentModificationException(e);
            }
        }

        /**
         * Resets the iterator to the starting position.
         */
        void reset() {
            current = start;
        }
    }
}
