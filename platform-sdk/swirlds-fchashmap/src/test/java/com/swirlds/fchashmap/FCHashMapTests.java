// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hiero.base.exceptions.ReferenceCountException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FCHashMap Tests")
public class FCHashMapTests {

    private static class MapPair {
        FCHashMap<Integer, String> fcHashMap;
        final Map<Integer, String> hashMap;

        MapPair(final FCHashMap<Integer, String> fcHashMap, final Map<Integer, String> hashMap) {
            this.fcHashMap = fcHashMap;
            this.hashMap = hashMap;
        }
    }

    public FCHashMapTests() {}

    /**
     * Verify that two maps contain the same keys and values.
     */
    void assertMapEquality(final FCHashMap<Integer, String> fcHashMap, final Map<Integer, String> hashMap) {
        assertEquals(fcHashMap.size(), hashMap.size());
        for (final Map.Entry<Integer, String> entry : fcHashMap.entrySet()) {
            Assertions.assertEquals(hashMap.get(entry.getKey()), entry.getValue());
        }

        for (final Map.Entry<Integer, String> entry : hashMap.entrySet()) {
            assertEquals(fcHashMap.get(entry.getKey()), entry.getValue());
        }
    }

    /**
     * For each copy that has been made ensure that the reference copy is the same.
     */
    void assertAllCopiesAreEqual(final Collection<MapPair> equivalentCopies) {
        for (final MapPair pair : equivalentCopies) {
            assertMapEquality(pair.fcHashMap, pair.hashMap);
        }
    }

    /**
     * Make copies of a fcHashMap and its HashMap reference. Add those copies to the list of copies that
     * are continuously checked for equality.
     *
     * @return The mutable copy of the FCHashMap.
     */
    FCHashMap<Integer, String> makeCopies(
            final Collection<MapPair> equivalentCopies,
            final FCHashMap<Integer, String> fcHashMap,
            final HashMap<Integer, String> hashMap) {
        // Deep copy the hash map
        final Map<Integer, String> hashMapCopy = new HashMap<>();
        for (final Map.Entry<Integer, String> entry : hashMap.entrySet()) {
            hashMapCopy.put(entry.getKey(), entry.getValue());
        }

        final FCHashMap<Integer, String> mutableCopy = fcHashMap.copy();

        equivalentCopies.add(new MapPair(fcHashMap, hashMapCopy));

        return mutableCopy;
    }

    /**
     * Make copies of a map and verify that this copy and all previous copies contain the correct values.
     *
     * @return the mutable copy of the FCHashMap
     */
    FCHashMap<Integer, String> makeCopiesAndVerify(
            final Collection<MapPair> equivalentCopies,
            final FCHashMap<Integer, String> fcHashMap,
            final HashMap<Integer, String> hashMap) {
        final FCHashMap<Integer, String> mutableCopy = makeCopies(equivalentCopies, fcHashMap, hashMap);
        assertAllCopiesAreEqual(equivalentCopies);
        return mutableCopy;
    }

    /**
     * Put a value into the map and ensure that the fcHashMap and HashMap are equivalent.
     * Also checks all existing copies to make sure they haven't changed.
     */
    void put(
            final Collection<MapPair> equivalentCopies,
            final FCHashMap<Integer, String> fcHashMap,
            final HashMap<Integer, String> hashMap,
            final Integer key,
            final String value) {
        fcHashMap.put(key, value);
        hashMap.put(key, value);
        assertMapEquality(fcHashMap, hashMap);
        assertAllCopiesAreEqual(equivalentCopies);
    }

    /**
     * Remove a value from the map and ensure that the fcHashMap and HashMap are equivalent.
     * Also checks all existing copies to make sure they haven't changed.
     */
    void remove(
            final Collection<MapPair> equivalentCopies,
            final FCHashMap<Integer, String> fcHashMap,
            final HashMap<Integer, String> hashMap,
            final Integer key) {
        fcHashMap.remove(key);
        hashMap.remove(key);
        assertMapEquality(fcHashMap, hashMap);
        assertAllCopiesAreEqual(equivalentCopies);
    }

    /**
     * Clear the map and ensure that the fcHashMap and HashMap are equivalent.
     * Also checks all existing copies to make sure they haven't changed.
     */
    void clear(
            final Collection<MapPair> equivalentCopies,
            final FCHashMap<Integer, String> fcHashMap,
            final HashMap<Integer, String> hashMap) {
        fcHashMap.clear();
        hashMap.clear();
        assertMapEquality(fcHashMap, hashMap);
        assertAllCopiesAreEqual(equivalentCopies);
    }

    /**
     * Basic sanity test.
     *
     * Perform a sequence of operations on a fcHashMap. Perform those same operations on a HashMap and assert
     * that the fcHashMap and HashMap contain the same values.
     */
    @Test
    @DisplayName("Basic Behavior")
    void basicBehavior() {
        final List<MapPair> equivalentCopies = new LinkedList<>();

        final FCHashMap<Integer, String> fcHashMap = new FCHashMap<>();
        final HashMap<Integer, String> hashMap = new HashMap<>();

        final FCHashMap<Integer, String> fcHashMap01 = makeCopies(equivalentCopies, fcHashMap, hashMap);

        put(equivalentCopies, fcHashMap01, hashMap, 0, "a");
        put(equivalentCopies, fcHashMap01, hashMap, 0, "b");
        put(equivalentCopies, fcHashMap01, hashMap, 0, "c"); // Update a value multiple times in one copy
        put(equivalentCopies, fcHashMap01, hashMap, 1, "d");
        put(equivalentCopies, fcHashMap01, hashMap, 2, "e");
        put(equivalentCopies, fcHashMap01, hashMap, 2, "f");
        put(equivalentCopies, fcHashMap01, hashMap, 2, "g");
        put(equivalentCopies, fcHashMap01, hashMap, 2, "h");
        put(equivalentCopies, fcHashMap01, hashMap, 3, "i");

        final FCHashMap<Integer, String> fcHashMap02 = makeCopiesAndVerify(equivalentCopies, fcHashMap01, hashMap);

        put(equivalentCopies, fcHashMap02, hashMap, 0, "j"); // Over-write data preserved in previous copy
        put(equivalentCopies, fcHashMap02, hashMap, 2, "k");
        put(equivalentCopies, fcHashMap02, hashMap, 3, "l");
        put(equivalentCopies, fcHashMap02, hashMap, 4, "m"); // Write a value not in the previous copy

        final FCHashMap<Integer, String> fcHashMap03 = makeCopiesAndVerify(equivalentCopies, fcHashMap02, hashMap);

        remove(equivalentCopies, fcHashMap03, hashMap, 3); // Remove a key
        remove(equivalentCopies, fcHashMap03, hashMap, 9999); // Remove a key that does not exist
        put(equivalentCopies, fcHashMap03, hashMap, 5, "k");

        final FCHashMap<Integer, String> fcHashMap04 = makeCopiesAndVerify(equivalentCopies, fcHashMap03, hashMap);

        clear(equivalentCopies, fcHashMap04, hashMap); // Clear the map

        put(equivalentCopies, fcHashMap04, hashMap, 0, "w"); // Write something that collides with previous
        // value before clear
        put(equivalentCopies, fcHashMap04, hashMap, 1, "x");
        put(equivalentCopies, fcHashMap04, hashMap, 3, "y");
        put(equivalentCopies, fcHashMap04, hashMap, 6, "z");

        final FCHashMap<Integer, String> fcHashMap05 = makeCopiesAndVerify(equivalentCopies, fcHashMap04, hashMap);

        put(equivalentCopies, fcHashMap05, hashMap, 0, "0");
        remove(equivalentCopies, fcHashMap05, hashMap, 0);
        put(equivalentCopies, fcHashMap05, hashMap, 0, "1"); // Remove a value then put it back into the map
        // on same copy
        fcHashMap.release();
        fcHashMap01.release();
        fcHashMap02.release();
        fcHashMap03.release();
        fcHashMap04.release();
        fcHashMap05.release();
    }

    /**
     * Runs in a background thread and continuously verifies that all map copies are equivalent.
     */
    private class BackgroundVerifier implements Runnable {

        private volatile boolean alive;
        private final Collection<MapPair> equivalentCopies;
        private final Thread backgroundChecker;

        public BackgroundVerifier(final Collection<MapPair> equivalentCopies) {
            this.alive = true;
            this.equivalentCopies = equivalentCopies;
            this.backgroundChecker = new Thread(this);
            this.backgroundChecker.start();
        }

        public void stop() throws InterruptedException {
            alive = false;
            backgroundChecker.join();
        }

        @Override
        public void run() {
            while (alive) {
                assertAllCopiesAreEqual(equivalentCopies);
            }
        }
    }

    /**
     * While writing to the mutable FCHashMap copy, read from the old copies in another thread.
     */
    @Test
    @DisplayName("Parallel FCHashMap")
    void parallelFCHashMap() throws InterruptedException {

        final Queue<MapPair> equivalentCopies = new ConcurrentLinkedQueue<>();
        FCHashMap<Integer, String> fcHashMap = new FCHashMap<>();
        final HashMap<Integer, String> hashMap = new HashMap<>();

        final BackgroundVerifier verifier = new BackgroundVerifier(equivalentCopies);

        final int numberOfCopies = 100;
        final int changesPerCopy = 30;
        final int maximumKey = 50;

        for (int copyNumber = 0; copyNumber < numberOfCopies; copyNumber++) {
            for (int changeNumber = 0; changeNumber < changesPerCopy; changeNumber++) {

                // Chose a key using in a random-ish way
                final int key = (copyNumber * changeNumber) % maximumKey;

                if ((copyNumber + changeNumber) % 10 == 0) {
                    // Delete a value ever 10th operation
                    if (hashMap.containsKey(key)) {
                        remove(equivalentCopies, fcHashMap, hashMap, key);
                    }
                } else {
                    final String value = copyNumber + "_" + changeNumber;
                    put(equivalentCopies, fcHashMap, hashMap, key, value);
                }
            }

            fcHashMap = makeCopies(equivalentCopies, fcHashMap, hashMap);
            // Sleep for a little while to ensure that the verification thread is keeping up
            Thread.sleep(10);
        }

        verifier.stop();
    }

    /**
     * There was once a bug where the purging process would crash if a key was created and deleted before
     * the first copy is made. This test verifies that the bug is fixed.
     */
    @Test
    @DisplayName("Check For Deletion Error")
    void checkForDeletionError() throws InterruptedException {
        final FCHashMap<Integer, String> fcHashMap = new FCHashMap<>();

        // When this happens before first copy it used to crash the purging process
        fcHashMap.put(0, "a");
        fcHashMap.remove(0);

        assertEquals(0, fcHashMap.size(), "map should be empty");

        // Creating the first copy and deleting it will trigger the mutation of key 0 to be purged
        final FCHashMap<Integer, String> copy = fcHashMap.copy();
        copy.release();

        fcHashMap.release();
    }

    /**
     * This tests for a race condition that previously existed where a map would contain a null value for
     * a recently deleted key for a brief period of time.
     */
    @Test
    @DisplayName("Finality of Deletion")
    void finalityOfDeletion() {
        final FCHashMap<Integer, String> fcHashMap = new FCHashMap<>();

        assertEquals(0, fcHashMap.size(), "map should be empty");

        fcHashMap.put(0, "howdy");
        assertEquals(fcHashMap.size(), 1);
        assertTrue(fcHashMap.containsKey(0));

        fcHashMap.remove(0);
        assertFalse(fcHashMap.containsKey(0));

        fcHashMap.release();
    }

    /**
     * FCHashMap should return throw a nullptr exception when a null key is used
     */
    @Test
    @DisplayName("Null Key")
    void nullKey() {
        final FCHashMap<Integer, String> map = new FCHashMap<>();
        final Exception exception = assertThrows(NullPointerException.class, () -> map.get(null));
        assertEquals("Null keys are not allowed", exception.getMessage());
        map.release();
    }

    @DisplayName("Copy Throws If Immutable")
    @Test
    void copyThrowsIfImmutable() {
        final FCHashMap<Integer, String> map01 = new FCHashMap<>();
        final FCHashMap<Integer, String> map02 = map01.copy();
        final Exception exception = assertThrows(MutabilityException.class, map01::copy, "expected this to fail");
        assertEquals("This operation is not permitted on an immutable object.", exception.getMessage());
        map01.release();
        map02.release();
    }

    @Test
    @DisplayName("Copy Throws If Deleted")
    void copyThrowsIfDeleted() {
        final FCHashMap<Integer, String> map01 = new FCHashMap<>();
        final FCHashMap<Integer, String> map02 = map01.copy();
        map01.release();
        map02.release();
        assertThrows(ReferenceCountException.class, map02::copy, "expected exception");
    }

    /**
     * There used to be a bug in the FCHashMap purging process where this would cause an element to be
     * prematurely deleted. This bug has been fixed.
     */
    @Test
    @DisplayName("Early Deletion Bug")
    void earlyDeletionBug() {
        final int key = 123;
        final String string1 = "howdy";
        final String string2 = "this is a test of the emergency testing system";

        FCHashMap<Integer, String> map = new FCHashMap<>();

        map.put(key, string1);

        FCHashMap<Integer, String> copy = map.copy();
        map.release();
        map = copy;

        assertEquals(string1, map.get(key), "value does not match");

        map.put(key, string2);

        copy = map.copy();
        map.release();

        final FCHashMap<Integer, String> m = map;
        map = copy;

        assertEquals(string2, map.get(key), "value does not match");
        map.release();
    }

    @Test
    @DisplayName("Insert And Remove Quickly")
    void insertAndRemoveQuickly() {

        final int numberOfCopies = 1_000;
        final int modificationsPerCopy = 1_000;

        FCHashMap<Integer, Integer> fcHashMap = new FCHashMap<>();
        final Map<Integer, Integer> referenceMap = new HashMap<>();

        final int key = 0;
        final int value = 0;

        for (int copyIndex = 0; copyIndex < numberOfCopies; copyIndex++) {

            final FCHashMap<Integer, Integer> oldMap = fcHashMap;
            fcHashMap = fcHashMap.copy();
            oldMap.release();

            for (int i = 0; i < modificationsPerCopy; i++) {

                final Integer previousFCHashMapValue = fcHashMap.put(key, value);
                final Integer previousReverenceMapValue = referenceMap.put(key, value);
                assertEquals(
                        previousReverenceMapValue,
                        previousFCHashMapValue,
                        "value should match reference, copy index = " + i);

                final Integer previousFCHashMapRemoveValue = fcHashMap.remove(key);
                final Integer previousReverenceMapRemoveValue = referenceMap.remove(key);
                assertEquals(
                        previousReverenceMapRemoveValue,
                        previousFCHashMapRemoveValue,
                        "value should match reference, copy index = " + i);
            }
        }
    }

    @Test
    @DisplayName("Null Value Test")
    void nullValueTest() {
        final FCHashMap<Integer, Integer> map = new FCHashMap<>();
        assertThrows(NullPointerException.class, () -> map.put(0, null), "map should not accept null values");
        map.release();
    }

    /**
     * There was once a bug that caused incorrect behavior when sequential deletion records were collapsed.
     */
    @Test
    @DisplayName("Sequential Deletion Record Bug")
    void sequentialDeletionRecordBug() {
        final FCHashMap<Integer, Integer> map = new FCHashMap<>();
        map.put(0, 0);

        final FCHashMap<Integer, Integer> copy0 = map.copy();
        copy0.put(0, 1);

        final FCHashMap<Integer, Integer> copy1 = copy0.copy();

        // After this operation there will be 1 deletion record in the list of mutations
        copy1.remove(0);

        final FCHashMap<Integer, Integer> copy2 = copy1.copy();
        copy2.put(0, 2);
        // After this operation there will be 2 adjacent deletion records in the list of mutations
        copy2.remove(0);

        // Until we purge, everything should work as expected
        assertEquals(0, map.get(0), "map should contain value that was set");
        assertEquals(1, copy0.get(0), "map should contain value that was set");
        assertFalse(copy1.containsKey(0), "this copy should not have the key");
        assertFalse(copy2.containsKey(0), "this copy should not have the key");

        // Release the oldest map
        map.release();

        // When the bug was present, not all of these tests would pass
        assertEquals(1, copy0.get(0), "map should contain value that was set");
        assertFalse(copy1.containsKey(0), "this copy should not have the key");
        assertFalse(copy2.containsKey(0), "this copy should not have the key");

        copy0.release();
        copy1.release();
        copy2.release();
    }

    @Test
    @DisplayName("getForModify() test")
    void getForModifyTest() {
        final FCHashMap<Integer, FCInt> copy0 = new FCHashMap<>();

        assertNull(copy0.getForModify(0), "value is unset");

        final FCInt value0 = new FCInt(1234);
        copy0.put(0, value0);

        final ModifiableValue<FCInt> modifiableValue0 = copy0.getForModify(0);
        assertSame(value0, modifiableValue0.value(), "should not create copy in same round");
        assertSame(value0, modifiableValue0.original(), "original should point to the same value");

        final FCHashMap<Integer, FCInt> copy1 = copy0.copy();

        final ModifiableValue<FCInt> modifiableValue1 = copy1.getForModify(0);
        assertNotSame(value0, modifiableValue1.value(), "there should be a new value");
        assertSame(value0, modifiableValue1.original(), "original should point to the value from before");
        assertEquals(value0, modifiableValue1.value(), "copy should be equal");
        assertSame(value0, copy0.get(0), "original map should still contain the same value");
        assertSame(modifiableValue1.value(), copy1.get(0), "get should return the same value");

        assertNotNull(
                copy1.getFamily().getMutation(copy1.getVersion(), 0).getPrevious(),
                "previous value should not have been purged");

        copy0.release();

        assertNull(
                copy1.getFamily().getMutation(copy1.getVersion(), 0).getPrevious(),
                "previous value should have been purged");

        copy1.release();
    }

    @Test
    @DisplayName("getForModify() Deleted Value")
    void getForModifyDeletedValue() {
        final FCHashMap<Integer, FCInt> copy0 = new FCHashMap<>();
        copy0.put(0, new FCInt(0));

        final FCHashMap<Integer, FCInt> copy1 = copy0.copy();
        copy1.remove(0);

        assertNotNull(copy1.getFamily().getMutation(copy1.getVersion(), 0), "deletion record should be present");

        assertNull(copy1.getForModify(1), "value is not in the map");
        assertNull(copy1.getForModify(0), "value is deleted, should return null");

        assertNotNull(copy1.getFamily().getMutation(copy1.getVersion(), 0), "deletion record should be present");

        copy0.release();
        copy1.release();
    }

    @Test
    @DisplayName("Entry Set Unsupported Equals")
    void entrySetUnsupportedEquals() {
        final FCHashMap<Integer, String> map = new FCHashMap<>();
        final Set<Map.Entry<Integer, String>> entrySet1 = map.entrySet();
        final Set<Map.Entry<Integer, String>> entrySet2 = map.entrySet();

        assertThrows(
                UnsupportedOperationException.class,
                () -> entrySet1.equals(entrySet2),
                "Equals shouldn't be supported");
    }

    @Test
    @DisplayName("Entry Set Unsupported HashCode")
    void entrySetUnsupportedHashCode() {
        final FCHashMap<Integer, String> map = new FCHashMap<>();
        final Set<Map.Entry<Integer, String>> entrySet = map.entrySet();

        assertThrows(UnsupportedOperationException.class, entrySet::hashCode, "HashCode shouldn't be supported");
    }

    @Test
    @DisplayName("initialInjection() Test")
    void initialInjectionTest() {
        final FCHashMap<Integer, String> map = new FCHashMap<>();

        for (int i = 0; i < 100; i++) {
            map.initialInjection(i, Integer.toString(i));
        }

        assertEquals(0, map.size(), "size should not yet be initialized");
        map.initialResize();
        assertEquals(100, map.size(), "map should now have proper size");

        for (int i = 0; i < 100; i++) {
            assertEquals(Integer.toString(i), map.get(i), "unexpected value");
        }
    }
}
