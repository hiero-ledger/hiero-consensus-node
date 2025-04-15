// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.FastCopyable;
import com.swirlds.fchashmap.internal.FCHashMapEntrySet;
import com.swirlds.fchashmap.internal.FCHashMapFamily;
import com.swirlds.fchashmap.internal.Mutation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.AbstractMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.exceptions.ReferenceCountException;

/**
 * <p>
 * A map that with {@link java.util.HashMap HashMap} like O(1) performance that provides {@link FastCopyable}
 * semantics.
 * </p>
 *
 * <p>
 * All operations are thread safe if performed simultaneously on different copies of the map.
 * </p>
 *
 * <p>
 * It is always thread safe to read unreleased immutable copies (as long as not done concurrently with the deletion of
 * that copy).
 * </p>
 *
 * <p>
 * It is thread safe safe to read and write simultaneously to the mutable copy of the map. {@link #size} may return
 * incorrect results if executed concurrently with an operation that modifies the size.
 * </p>
 *
 * <p>
 * The following operations are not thread safe:
 * </p>
 *
 * <ul>
 *     <li>calling {@link #copy()} concurrently with write operations</li>
 *     <li>calling {@link #release()} concurrently with read or write operations</li>
 * </ul>
 *
 * @param <K> the type of the key
 * @param <V> the type of the value
 */
public class FCHashMap<K, V> extends AbstractMap<K, V> implements FastCopyable {

    /**
     * split the binary tree at this depth relative to the root of the binary tree. The tree will be split into
     * 2^split-factor subtrees, and each subtree will be eligible to be handled on a separate thread.
     */
    public static final int REBUILD_SPLIT_FACTOR = 7;

    /**
     * rebuilding the FCHashMap in a MerkleMap, use this many threads to rebuild the tree.
     */
    public static final int REBUILD_THREAD_COUNT = 24;

    /**
     * When a copy of an FCHashMap is made, that copy is in the same family as the original. A sequence of copies form a
     * single family. The FCHashMapFamily object manages the data shared between copies in a family, as well as managing
     * the lifecycle and eventual deletion of data when it is no longer needed by any un-deleted copy in the family.
     */
    final FCHashMapFamily<K, V> family;

    /**
     * Monotonically increasing version number that is incremented every time copy() is called on the mutable copy.
     */
    private final long version;

    /**
     * Is this object a mutable object?
     */
    private boolean immutable;

    /**
     * The current size of the map.
     */
    private final AtomicInteger size;

    /**
     * Tracks if this particular object has been deleted.
     */
    private final AtomicBoolean released = new AtomicBoolean(false);

    /**
     * Create a new FCHashMap.
     */
    public FCHashMap() {
        this(0);
    }

    /**
     * Create a new FCHashMap.
     *
     * @param capacity the initial capacity of the map
     */
    public FCHashMap(final int capacity) {

        family = new FCHashMapFamily<>(capacity);
        version = 0;

        immutable = false;
        size = new AtomicInteger(0);
    }

    /**
     * Copy constructor.
     *
     * @param that the map to copy
     */
    private FCHashMap(final FCHashMap<K, V> that) {
        this.family = that.family;
        this.version = family.copyMap();
        size = new AtomicInteger(that.size.get());

        immutable = false;
    }

    /**
     * Get the {@link FCHashMapFamily} that this map belongs to.
     */
    FCHashMapFamily<K, V> getFamily() {
        return family;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public synchronized FCHashMap<K, V> copy() {
        throwIfImmutable();
        throwIfDestroyed();
        try {
            return new FCHashMap<>(this);
        } finally {
            this.immutable = true;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return this.immutable;
    }

    /**
     * <p>
     * Use this to clean up resources held by this copy. Failure to call delete on a copy before it is garbage collected
     * will result in a memory leak.
     * </p>
     *
     * <p>
     * Not thread safe. Must not be called at the same time another thread is attempting to read from this copy.
     * </p>
     */
    @Override
    public synchronized boolean release() {
        final boolean previouslyReleased = released.getAndSet(true);
        if (previouslyReleased) {
            throw new ReferenceCountException("this object has already been released");
        }
        family.releaseMap(version);
        return true;
    }

    /**
     * Check to see if this copy has been deleted.
     */
    @Override
    public boolean isDestroyed() {
        return released.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return size.get();
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean containsKey(final Object key) {
        final Mutation<V> mutation = family.getMutation(version, (K) key);
        return mutation != null && mutation.getValue() != null;
    }

    /**
     * <p>
     * Directly inject a value into the map. This method designed for rapid initialization of an FCHashMap, and should
     * only be used on maps being initialized. Should not be called after the map has been copied or modified by any
     * method other than this method..
     * </p>
     *
     * <p>
     * This method does not update the size of the data structure. After all injections have been completed, call
     * {@link #initialResize()}.
     * </p>
     *
     * <p>
     * It is thread safe to use this method concurrently.
     * </p>
     */
    public void initialInjection(final K key, final V value) {
        family.getData().put(key, new Mutation<>(version, value, null));
    }

    /**
     * This method MUST be called if the FCHashMap has been initialized using {@link #initialInjection(Object, Object)}.
     * If called, must be called before any copies of the map are made or any modifications are made by any method other
     * than {@link #initialInjection(Object, Object)}.
     */
    public void initialResize() {
        size.set(family.getData().size());
    }

    /**
     * Returns the version of the copy.
     *
     * @return the version of the copy
     */
    public long getVersion() {
        return version;
    }

    /**
     * Not thread safe on an immutable copy of the map if it is possible that another thread may have deleted the map
     * copy. Map deletion and reads against the map must be externally synchronized. The function hasBeenDeleted() can
     * be used to check to see if the copy has been deleted.
     */
    @SuppressWarnings("unchecked")
    @Override
    public V get(final Object key) {
        if (key == null) {
            throw new NullPointerException("Null keys are not allowed");
        }
        final Mutation<V> mutation = family.getMutation(version, (K) key);
        return mutation == null ? null : mutation.getValue();
    }

    /**
     * <p>
     * Get a value that is safe to directly modify. If value has been modified this round then return it. If value was
     * modified in a previous round, call {@link FastCopyable#copy()} on it, insert it into the map, and return it. If
     * the value is null, then return null.
     * </p>
     *
     * <p>
     * It is not necessary to manually re-insert the returned value back into the map.
     * </p>
     *
     * <p>
     * This method is only permitted to be used on maps that contain values that implement {@link FastCopyable}. Using
     * this method on maps that contain values that do not implement {@link FastCopyable} will result in undefined
     * behavior.
     * </p>
     *
     * @param key the key
     * @return a {@link ModifiableValue} that contains a value is safe to directly modify, or null if the key is not in
     * the map
     */
    public ModifiableValue<V> getForModify(final K key) {
        throwIfImmutable();
        return family.getForModify(key);
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the key or value is null
     */
    @Override
    public V put(@NonNull final K key, @NonNull final V value) {
        requireNonNull(key, "key must not be null");
        requireNonNull(value, "value must not be null");
        throwIfImmutable();
        return family.mutate(key, value, size);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public V remove(@NonNull final Object key) {
        requireNonNull(key, "key must not be null");
        throwIfImmutable();
        return family.mutate((K) key, null, size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        for (final K k : keySet()) {
            remove(k);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        return new FCHashMapEntrySet<>(this, family);
    }
}
