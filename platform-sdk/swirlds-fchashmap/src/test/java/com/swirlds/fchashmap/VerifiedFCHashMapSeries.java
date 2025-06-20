// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.FastCopyable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assertions;

/**
 * A series of FCHashMap copies. Each copy is paired with a standard HashMap which is used to
 * validate the contents of each copy.
 *
 * @param <K>
 * 		the type of the key
 * @param <V>
 * 		the type of the value
 */
public class VerifiedFCHashMapSeries<K, V extends FastCopyable> {

    private final List<Pair<Map<K, V>, FCHashMap<K, V>>> copies;

    public VerifiedFCHashMapSeries() {
        copies = new LinkedList<>();
        final FCHashMap<K, V> map = new FCHashMap<>();
        copies.add(Pair.of(new HashMap<>(), map));
    }

    /**
     * Throw an exception if all copies have been released.
     */
    private void throwIfReleased() {
        assertFalse(copies.isEmpty(), "can not perform operations if all copies have been released");
    }

    /**
     * Get the pair containing the mutable copy.
     */
    private Pair<Map<K, V>, FCHashMap<K, V>> getMutablePair() {
        return copies.get(copies.size() - 1);
    }

    /**
     * Make a copy of the latest reference map.
     */
    private Map<K, V> makeReferenceCopy() {
        final Map<K, V> reference = getMutablePair().left();

        final Map<K, V> referenceCopy = new HashMap<>();

        for (final Map.Entry<K, V> entry : reference.entrySet()) {
            final V value = entry.getValue();
            referenceCopy.put(entry.getKey(), value.copy());
        }

        return referenceCopy;
    }

    /**
     * Make a copy of the mutable FCHashMap.
     */
    private FCHashMap<K, V> makeFCHashMapCopy() {
        return getMutablePair().right().copy();
    }

    /**
     * Make a copy of the map and its reference.
     */
    public void copy() {
        throwIfReleased();
        copies.add(Pair.of(makeReferenceCopy(), makeFCHashMapCopy()));
    }

    /**
     * Release a copy. The oldest copy in memory has index 0.
     *
     * @param index
     * 		the index of the copy to release
     */
    public void release(final int index) {
        throwIfReleased();
        final FCHashMap<K, V> map = copies.get(index).right();
        if (!map.isImmutable()) {
            assertEquals(1, copies.size(), "mutable map must be released last");
        }
        map.release();
        copies.remove(index);
    }

    /**
     * Release on of the copies, chosen randomly. Mutable copy is never released as long as there is at least
     * one immutable copy.
     */
    public void releaseRandom(final Random random) {
        throwIfReleased();

        final int index;
        if (copies.size() <= 2) {
            index = 0;

        } else {
            index = random.nextInt(copies.size() - 2);
        }

        release(index);
    }

    /**
     * Get the number of copies that have not been released, including the mutable copy.
     */
    public int getNumberOfCopies() {
        return copies.size();
    }

    /**
     * Put a value into the map and its reference. Value must be provided twice to satisfy mutability constraints.
     */
    public void put(final K key, final V value, final V valueCopy) {
        throwIfReleased();
        assertEquals(value, valueCopy, "value and its copy must be equal");

        final Pair<Map<K, V>, FCHashMap<K, V>> pair = getMutablePair();
        final Map<K, V> reference = pair.left();
        final FCHashMap<K, V> map = pair.right();

        final V previous = map.put(key, value);
        final V previousReference = reference.put(key, valueCopy);

        assertEquals(previousReference, previous, "map should have same previous value as reference map");
    }

    /**
     * Remove a value from the map and its reference
     */
    public void remove(final K key) {
        throwIfReleased();

        final Pair<Map<K, V>, FCHashMap<K, V>> pair = getMutablePair();
        final Map<K, V> reference = pair.left();
        final FCHashMap<K, V> map = pair.right();

        final V previousReference = reference.remove(key);
        final V previous = map.remove(key);

        assertEquals(previousReference, previous, "map should have same previous value as reference map");
    }

    /**
     * The result of getForModify()
     *
     * @param referenceValue
     * 		the value from the reference map
     * @param value
     * 		the value from the FCHashMap
     */
    public record ModifiableValues<V>(V referenceValue, V value) {}

    /**
     * Call getForModify() on the map and simulate it on the reference copy.
     */
    public ModifiableValues<V> getForModify(final K key) {
        throwIfReleased();
        final Pair<Map<K, V>, FCHashMap<K, V>> pair = getMutablePair();
        final Map<K, V> reference = pair.left();
        final FCHashMap<K, V> map = pair.right();

        final V referenceValue = reference.get(key);
        final ModifiableValue<V> modifiableValue = map.getForModify(key);
        final V value = modifiableValue == null ? null : modifiableValue.value();

        assertEquals(referenceValue, value);

        return new ModifiableValues<>(referenceValue, value);
    }

    /**
     * Assert that a single copy is valid.
     */
    private void assertCopyValidity(final Pair<Map<K, V>, FCHashMap<K, V>> pair) {
        final Map<K, V> reference = pair.left();
        final FCHashMap<K, V> map = pair.right();

        Assertions.assertEquals(reference.size(), map.size(), "size of map should match the reference");

        for (final Map.Entry<K, V> entry : reference.entrySet()) {
            Assertions.assertEquals(entry.getValue(), map.get(entry.getKey()), "value should match reference map");
        }
    }

    /**
     * Assert that all copies match their reference maps.
     */
    public void assertValidity() {
        for (final Pair<Map<K, V>, FCHashMap<K, V>> pair : copies) {
            assertCopyValidity(pair);
        }
    }

    /**
     * Get the internal list of copies.
     */
    public List<Pair<Map<K, V>, FCHashMap<K, V>>> getCopies() {
        return copies;
    }
}
