// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import com.swirlds.base.internal.BaseExecutorFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * A concurrent map that at given frequency shrinks back to a max size by removing the eldest entries. This map size is
 * not fixed, it will eventually shrink-back to its maximum.
 *
 * @param <K> the type for key
 * @param <V> the type for value
 */
public class ShrinkableSizeCache<K, V> implements Map<K, V> {
    public static final int MAX_ENTRIES = 10000;
    public static final int SHRINK_PERIOD_MS = 1000;
    private final Deque<K> insertionOrderList = new ConcurrentLinkedDeque<>();
    private final Map<K, V> delegatedMap = new ConcurrentHashMap<>();

    /**
     * Creates a ShrinkableSizeCache with MAX_ENTRIES max size and SHRINK_PERIOD_MS shrink period.
     */
    public ShrinkableSizeCache() {
        this(ShrinkableSizeCache.MAX_ENTRIES);
    }

    /**
     * Creates a ShrinkableSizeCache with configurable max size and SHRINK_PERIOD_MS shrink period.
     *
     * @param maxSize configurable max size for the cache
     */
    public ShrinkableSizeCache(final int maxSize) {
        this(maxSize, SHRINK_PERIOD_MS);
    }

    /**
     * Creates a ShrinkableSizeCache with configurable max size and shrink period.
     *
     * @param maxSize          configurable max size for the cache
     * @param shrinkPeriodInMs configurable shrink period for the cache
     */
    public ShrinkableSizeCache(final int maxSize, final int shrinkPeriodInMs) {
        Runnable cleanUpTask = () -> {
            while (insertionOrderList.size() > maxSize) {
                delegatedMap.remove(insertionOrderList.pop());
            }
            ShrinkableSizeCache.this.afterUpdate();
        };
        BaseExecutorFactory.getInstance()
                .getScheduledExecutor()
                .scheduleAtFixedRate(cleanUpTask, shrinkPeriodInMs, shrinkPeriodInMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Hook sub instances can use to perform single activities after the cleanup was done
     */
    protected void afterUpdate() {}

    /**
     * Returns the number of key-value mappings in this map.  If the map contains more than {@code Integer.MAX_VALUE}
     * elements, returns {@code Integer.MAX_VALUE}.
     *
     * @return the number of key-value mappings in this map
     */
    @Override
    public int size() {
        return delegatedMap.size();
    }

    /**
     * Returns {@code true} if this map contains no key-value mappings.
     *
     * @return {@code true} if this map contains no key-value mappings
     */
    @Override
    public boolean isEmpty() {
        return delegatedMap.isEmpty();
    }

    /**
     * Returns {@code true} if this map contains a mapping for the specified key.  More formally, returns {@code true}
     * if and only if this map contains a mapping for a key {@code k} such that {@code Objects.equals(key, k)}.  (There
     * can be at most one such mapping.)
     *
     * @param key key whose presence in this map is to be tested
     * @return {@code true} if this map contains a mapping for the specified key
     * @throws ClassCastException   if the key is of an inappropriate type for this map
     *                              ({@linkplain Collection##optional-restrictions optional})
     * @throws NullPointerException if the specified key is null and this map does not permit null keys
     *                              ({@linkplain Collection##optional-restrictions optional})
     */
    @Override
    public boolean containsKey(final Object key) {
        return delegatedMap.containsKey(key);
    }

    /**
     * Returns {@code true} if this map maps one or more keys to the specified value.  More formally, returns
     * {@code true} if and only if this map contains at least one mapping to a value {@code v} such that
     * {@code Objects.equals(value, v)}.  This operation will probably require time linear in the map size for most
     * implementations of the {@code Map} interface.
     *
     * @param value value whose presence in this map is to be tested
     * @return {@code true} if this map maps one or more keys to the specified value
     * @throws ClassCastException   if the value is of an inappropriate type for this map
     *                              ({@linkplain Collection##optional-restrictions optional})
     * @throws NullPointerException if the specified value is null and this map does not permit null values
     *                              ({@linkplain Collection##optional-restrictions optional})
     */
    @Override
    public boolean containsValue(final Object value) {
        return delegatedMap.containsValue(value);
    }

    /**
     * Returns the value to which the specified key is mapped, or {@code null} if this map contains no mapping for the
     * key.
     *
     * <p>More formally, if this map contains a mapping from a key
     * {@code k} to a value {@code v} such that {@code Objects.equals(key, k)}, then this method returns {@code v};
     * otherwise it returns {@code null}.  (There can be at most one such mapping.)
     *
     * <p>If this map permits null values, then a return value of
     * {@code null} does not <i>necessarily</i> indicate that the map contains no mapping for the key; it's also
     * possible that the map explicitly maps the key to {@code null}.  The {@link #containsKey containsKey} operation
     * may be used to distinguish these two cases.
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or {@code null} if this map contains no mapping for the
     * key
     * @throws ClassCastException   if the key is of an inappropriate type for this map
     *                              ({@linkplain Collection##optional-restrictions optional})
     * @throws NullPointerException if the specified key is null and this map does not permit null keys
     *                              ({@linkplain Collection##optional-restrictions optional})
     */
    @Override
    public V get(final Object key) {
        return delegatedMap.get(key);
    }

    /**
     * Associates the specified value with the specified key in this map (optional operation).  If the map previously
     * contained a mapping for the key, the old value is replaced by the specified value.  (A map {@code m} is said to
     * contain a mapping for a key {@code k} if and only if {@link #containsKey(Object) m.containsKey(k)} would return
     * {@code true}.)
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or {@code null} if there was no mapping for {@code key}.
     * (A {@code null} return can also indicate that the map previously associated {@code null} with {@code key}, if the
     * implementation supports {@code null} values.)
     * @throws UnsupportedOperationException if the {@code put} operation is not supported by this map
     * @throws ClassCastException            if the class of the specified key or value prevents it from being stored in
     *                                       this map
     * @throws NullPointerException          if the specified key or value is null and this map does not permit null
     *                                       keys or values
     * @throws IllegalArgumentException      if some property of the specified key or value prevents it from being
     *                                       stored in this map
     */
    @Override
    public V put(final K key, final V value) {
        delegatedMap.computeIfPresent(key, (k, v) -> value);
        delegatedMap.computeIfAbsent(key, k -> {
            insertionOrderList.add(k);
            return value;
        });
        return value;
    }

    /**
     * the {@code remove} operation is not supported by this map
     *
     * @throws UnsupportedOperationException the {@code remove} operation is not supported by this map
     */
    @Override
    public V remove(final Object key) {
        throw new UnsupportedOperationException("Unsupported operation");
    }

    /**
     * the {@code putAll} operation is not supported by this map
     *
     * @throws UnsupportedOperationException the {@code putAll} operation is not supported by this map
     */
    @Override
    public void putAll(final Map<? extends K, ? extends V> m) {
        throw new UnsupportedOperationException("Unsupported operation");
    }

    /**
     * the {@code clear} operation is not supported by this map
     *
     * @throws UnsupportedOperationException the {@code clear} operation is not supported by this map
     */
    @Override
    public void clear() {
        throw new UnsupportedOperationException("Unsupported operation");
    }

    /**
     * @return a set view of the keys contained in this map
     */
    @NonNull
    @Override
    public Set<K> keySet() {
        return new HashSet<>(insertionOrderList);
    }

    /**
     * @return a collection view of the values contained in this map
     */
    @NonNull
    @Override
    public Collection<V> values() {
        return delegatedMap.values();
    }

    /**
     * @return a set view of the mappings contained in this map
     */
    @NonNull
    @Override
    public Set<Entry<K, V>> entrySet() {
        return delegatedMap.entrySet();
    }

    /**
     * {@link ShrinkableSizeCache} can only be equals by matching references
     */
    @Override
    public boolean equals(final Object o) {
        return super.equals(o);
    }

    /**
     * Returns the hash code value for this map.  The hash code of a map is defined to be the sum of the hash codes of
     * each entry in the map's {@code entrySet()} view.  This ensures that {@code m1.equals(m2)} implies that
     * {@code m1.hashCode()==m2.hashCode()} for any two maps {@code m1} and {@code m2}, as required by the general
     * contract of {@link Object#hashCode}.
     *
     * @return the hash code value for this map
     * @see Entry#hashCode()
     * @see Object#equals(Object)
     * @see #equals(Object)
     */
    @Override
    public int hashCode() {
        return Objects.hash(delegatedMap, insertionOrderList);
    }
}
