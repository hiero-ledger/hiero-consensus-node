// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.utility.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InverseFCHashMap Tests")
class FCInvertibleHashMapTests {

    /**
     * Given a map, build a inverse mapping between values and all keys that refer to a given value.
     */
    private static Map<Integer, Set<Integer>> buildInverseReferenceMap(final Map<Integer, Integer> referenceMap) {

        final Map<Integer, Set<Integer>> map = new HashMap<>();

        for (final Integer value : referenceMap.values()) {
            final Set<Integer> keySet = new HashSet<>();
            map.put(value, keySet);
            for (final Integer key : referenceMap.keySet()) {
                if (Objects.equals(referenceMap.get(key), value)) {
                    keySet.add(key);
                }
            }
        }

        return map;
    }

    /**
     * Ensure that the data in the invertible map matches the data in the reference map
     */
    private static void checkValidity(
            final List<Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>>> mapPairs) {

        for (final Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>> pair : mapPairs) {

            final Map<Integer, Integer> referenceMap = pair.left();
            final FCInvertibleHashMap<Integer, Integer> map = pair.right();

            assertEquals(referenceMap.keySet(), map.keySet(), "maps should have the same keys");

            for (final Integer key : referenceMap.keySet()) {
                assertEquals(referenceMap.get(key), map.get(key), "values should be the same in each map");
            }

            final Map<Integer, Set<Integer>> inverseReferenceMap = buildInverseReferenceMap(referenceMap);
            for (final Integer value : referenceMap.values()) {

                final HashSet<Integer> keys = new HashSet<>();
                final Iterator<Integer> iterator = map.inverseGet(value);
                while (iterator.hasNext()) {
                    keys.add(iterator.next());
                }

                assertEquals(inverseReferenceMap.get(value).size(), map.getValueCount(value), "expected size to match");
                assertEquals(inverseReferenceMap.get(value), keys, "inverse mapping should match");
            }
        }
    }

    /**
     * Insert an object into the map and check its validity
     */
    private static void put(
            final Map<Integer, Integer> referenceMap,
            final FCInvertibleHashMap<Integer, Integer> map,
            final Integer key,
            final Integer value) {

        final Integer referenceValue = referenceMap.put(key, value);
        final Integer prevValue = map.put(key, value);

        assertEquals(referenceValue, prevValue, "value returned by put should match reference value");
    }

    /**
     * Remove an object into the map and check its validity
     */
    private static void remove(
            final Map<Integer, Integer> referenceMap,
            final FCInvertibleHashMap<Integer, Integer> map,
            final Integer key) {

        final Integer referenceValue = referenceMap.remove(key);
        final Integer value = map.remove(key);

        assertEquals(referenceValue, value, "value returned by remove should match reference value");
    }

    private static void copy(final List<Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>>> mapPairs) {

        final Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>> lastPair =
                mapPairs.get(mapPairs.size() - 1);

        final Map<Integer, Integer> referenceMapCopy = new HashMap<>(lastPair.left());
        final FCInvertibleHashMap<Integer, Integer> mapCopy = lastPair.right().copy();

        mapPairs.add(Pair.of(referenceMapCopy, mapCopy));
    }

    private static void deleteNthOldestCopy(
            final List<Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>>> mapPairs,
            final int copyToDelete) {

        if (copyToDelete == mapPairs.size() - 1 && mapPairs.size() > 1) {
            throw new IllegalArgumentException("mutable copy should be deleted last");
        }

        final Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>> pair = mapPairs.get(copyToDelete);

        pair.right().release();

        mapPairs.remove(copyToDelete);
    }

    // This is copy-pasted, can't import properly until module is reorganized
    @Deprecated
    static Random getRandomPrintSeed() {
        long seed = new Random().nextLong();
        System.out.println("Random seed: " + seed);
        return new Random(seed);
    }

    @Test
    @DisplayName("Randomized Test")
    void randomizedTest() {

        final Random random = getRandomPrintSeed();

        // Time complexity is something like O(maxValue * numberOfKeys ^ 3), so don't choose large values.
        final int maxValue = 10;
        final int numberOfKeys = 300;

        final List<Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>>> mapPairs = new LinkedList<>();

        mapPairs.add(Pair.of(new HashMap<>(), new FCInvertibleHashMap<>()));

        checkValidity(mapPairs);

        // Add a bunch of key value pairs.
        // Every third operation also delete a random key.
        // Every tenth operation make a copy.
        // Every twentieth operation delete the oldest copy
        for (int i = 0; i < numberOfKeys; i++) {

            final Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>> lastPair =
                    mapPairs.get(mapPairs.size() - 1);

            final Map<Integer, Integer> referenceMap = lastPair.left();
            final FCInvertibleHashMap<Integer, Integer> map = lastPair.right();

            put(referenceMap, map, random.nextInt(), random.nextInt(maxValue));
            checkValidity(mapPairs);

            if (i != 0 && i % 3 == 0) {
                // Delete a value
                final List<Integer> keyList = new ArrayList<>(referenceMap.keySet());
                Integer key = keyList.get(random.nextInt(keyList.size()));
                remove(referenceMap, map, key);
                checkValidity(mapPairs);
            }

            if (i != 0 && i % 10 == 0) {
                // make a fast copy
                copy(mapPairs);
                checkValidity(mapPairs);
            }

            if (i != 0 && i % 20 == 0) {
                deleteNthOldestCopy(mapPairs, 0);
                checkValidity(mapPairs);
            }
        }

        // Remove all remaining keys
        final Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>> lastPair =
                mapPairs.get(mapPairs.size() - 1);

        final Map<Integer, Integer> referenceMap = lastPair.left();
        final FCInvertibleHashMap<Integer, Integer> map = lastPair.right();
        for (final Integer key : new ArrayList<>(referenceMap.keySet())) {
            remove(referenceMap, map, key);
            checkValidity(mapPairs);
        }

        // Release remaining maps in a random order
        while (mapPairs.size() > 1) {
            deleteNthOldestCopy(mapPairs, random.nextInt(mapPairs.size() - 1));
            checkValidity(mapPairs);
        }

        // Release the mutable copy
        deleteNthOldestCopy(mapPairs, 0);
        checkValidity(mapPairs);

        assertEquals(0, mapPairs.size(), "all maps should have been released");
    }

    @Test
    @DisplayName("Null Value Test")
    void nullValueTest() {
        final FCInvertibleHashMap<Integer, Integer> map = new FCInvertibleHashMap<>();
        assertThrows(IllegalArgumentException.class, () -> map.put(null, 10), "null key should throw exception");
        assertThrows(IllegalArgumentException.class, () -> map.put(10, null), "null value should throw exception");
        assertThrows(IllegalArgumentException.class, () -> map.inverseGet(null), "null value should throw exception");
        assertThrows(
                IllegalArgumentException.class, () -> map.inverseGet(null, 0), "null value should throw exception");
        assertThrows(
                IllegalArgumentException.class,
                () -> map.inverseGet(null, 0, 100),
                "null value should throw exception");
        map.release();
    }

    @Test
    @DisplayName("Out Of Order Deletion Test")
    void outOfOrderDeletionTest() {

        final Random random = getRandomPrintSeed();

        final List<Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>>> mapPairs = new LinkedList<>();

        mapPairs.add(Pair.of(new HashMap<>(), new FCInvertibleHashMap<>()));

        checkValidity(mapPairs);

        final int numberOfCopies = 20;
        final int modificationsPerCopy = 10;

        for (int copyNumber = 0; copyNumber < numberOfCopies; copyNumber++) {
            copy(mapPairs);
            checkValidity(mapPairs);

            final Pair<Map<Integer, Integer>, FCInvertibleHashMap<Integer, Integer>> lastPair =
                    mapPairs.get(mapPairs.size() - 1);

            for (int modificationNumber = 0; modificationNumber < modificationsPerCopy; modificationNumber++) {
                put(lastPair.left(), lastPair.right(), random.nextInt(10), random.nextInt());
            }
        }

        checkValidity(mapPairs);

        // Delete odd numbered copies in reverse age order
        for (int copyNumber = 19; copyNumber >= 1; copyNumber -= 2) {
            deleteNthOldestCopy(mapPairs, copyNumber);
            checkValidity(mapPairs);
        }

        // Delete remaining in forward order
        while (mapPairs.size() > 0) {
            deleteNthOldestCopy(mapPairs, 0);
            checkValidity(mapPairs);
        }
    }

    /**
     * This test is unable to pass with garbage collection disabled.
     */
    @Test
    @DisplayName("Garbage Collection Test")
    void garbageCollectionTest() {
        final Random random = getRandomPrintSeed();

        final int numberOfCopies = 10_000;
        final int modificationsPerCopy = 1_000;
        final int copiesToStore = 5;

        final LinkedList<FCInvertibleHashMap<Integer, byte[]>> copies = new LinkedList<>();
        copies.add(new FCInvertibleHashMap<>());

        for (int copyNumber = 0; copyNumber < numberOfCopies; copyNumber++) {

            copies.add(copies.getLast().copy());
            if (copies.size() > copiesToStore) {
                copies.getFirst().release();
                copies.remove(0);
            }

            for (int modificationNumber = 0; modificationNumber < modificationsPerCopy; modificationNumber++) {
                final byte[] data = new byte[100];
                random.nextBytes(data);
                copies.getLast().put(random.nextInt(1000), data);
            }
        }
    }

    /**
     * Verify that an iterator represents a subsection of a reference list.
     */
    private void verifyIterator(
            final FCInvertibleHashMap<Integer, Integer> map,
            final List<Integer> referenceList,
            final Iterator<Integer> iterator,
            final int beginIndex,
            final int endIndex) {

        assertEquals(referenceList, map.inverseGetList(beginIndex, endIndex), "list should match reference list");

        if (referenceList.size() == 0) {
            assertFalse(iterator.hasNext(), "iterator should not have any values");
            return;
        }

        for (int index = beginIndex; index < endIndex; index++) {
            assertTrue(iterator.hasNext(), "iterator should not yet be complete");
            assertEquals(referenceList.get(index), iterator.next(), "iterator should match reference value");
        }
        assertFalse(iterator.hasNext(), "iterator should be depleted");
    }

    @Test
    @DisplayName("Pagination Tests")
    void paginationTests() {

        final Random random = getRandomPrintSeed();

        final int maxKey = 100;

        // Generate a map
        final FCInvertibleHashMap<Integer, Integer> map = new FCInvertibleHashMap<>();
        for (int index = 0; index < 10_000; index++) {
            map.put(random.nextInt(maxKey), random.nextInt());
        }

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> map.inverseGet(0, 1, 0),
                "start index is greater than end index");
        assertThrows(IndexOutOfBoundsException.class, () -> map.inverseGet(0, -1, 0), "start index is negative");
        assertThrows(
                IndexOutOfBoundsException.class, () -> map.inverseGet(0, 100_000_000, 0), "start index is too high");
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> map.inverseGet(0, 100_000_000, 100_000_001),
                "end index is too high");

        for (int key = 0; key < maxKey; key++) {

            final List<Integer> fullList = new ArrayList<>();
            map.inverseGet(key).forEachRemaining(fullList::add);

            verifyIterator(map, fullList, map.inverseGet(key, 0), 0, fullList.size());
            verifyIterator(map, fullList, map.inverseGet(key, 0, fullList.size()), 0, fullList.size());

            if (fullList.size() >= 1) {
                verifyIterator(map, fullList, map.inverseGet(key, 1), 1, fullList.size());
                verifyIterator(map, fullList, map.inverseGet(key, 1, 100), 1, 100);
                verifyIterator(map, fullList, map.inverseGet(key, 0, 1), 0, 1);
            }
            if (fullList.size() >= 2) {
                verifyIterator(map, fullList, map.inverseGet(key, 2), 2, fullList.size());
                verifyIterator(map, fullList, map.inverseGet(key, 0, 2), 0, 2);
            }
            if (fullList.size() >= 3) {
                verifyIterator(map, fullList, map.inverseGet(key, 3), 3, fullList.size());
            }
            if (fullList.size() > 5) {
                verifyIterator(map, fullList, map.inverseGet(key, 1, 5), 1, 5);
            }
        }
    }
}
