// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import com.swirlds.fchashmap.internal.Mutation;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FCHashMap Garbage Collector Test")
class FCHashMapGarbageCollectorTest {

    /**
     * Check all mutations in all copies, verify that each mutation is required to exist.
     */
    private void assertValidity(final FCHashMapSeries<Integer, FCInt> copies) {
        final Map<Integer, Mutation<FCInt>> data =
                copies.getLatest().getFamily().getData();

        // Gather all existing mutations
        final Set<Mutation<FCInt>> mutations = new HashSet<>();
        for (final int key : data.keySet()) {
            Mutation<FCInt> mutation = data.get(key);
            assertNotNull(mutation, "all entries should have at least one mutation");
            while (mutation != null) {
                mutations.add(mutation);
                mutation = mutation.getPrevious();
            }
        }

        // Scan each copy of the map, removing the mutations we find from the mutation set
        for (final FCHashMap<Integer, FCInt> copy : copies) {
            for (final int key : data.keySet()) {
                final Mutation<FCInt> mutation = copy.getFamily().getMutation(copy.getVersion(), key);
                if (mutation == null) {
                    assertNull(copy.get(key), "if there is no mutation then the map should contain null value");
                    continue;
                }

                // For the sake of sanity, ensure that the mutation reflects the value the map holds
                if (mutation.getValue() == null) {
                    assertNull(copy.get(key), "mutation is deleted, map should return null");
                } else {
                    assertEquals(mutation.getValue(), copy.get(key), "map should contain value described by mutation");
                }

                mutations.remove(mutation);
            }
        }

        // Each mutation should be reachable from one of the copies, so the set should be empty
        assertTrue(mutations.isEmpty(), "all mutations should have been reachable");
    }

    @Test
    @DisplayName("Leak Detection Test")
    void leakDetectionTest() {

        final int maxKey = 10_000;
        final int iterations = 1_000_000;
        final int operationsPerCopy = 1000;
        final int copiesToKeep = 10;
        final int operationsPerValidation = 100_000;

        final FCHashMapSeries<Integer, FCInt> copies = new FCHashMapSeries<>();
        final Random random = getRandomPrintSeed();

        for (int iteration = 0; iteration < iterations; iteration++) {
            final FCHashMap<Integer, FCInt> map = copies.getLatest();

            // Insert a value
            map.put(random.nextInt(maxKey), new FCInt(random.nextInt()));

            // Delete a value
            final int keyToDelete = random.nextInt(maxKey);
            map.remove(keyToDelete);

            // Do a getForModify operation
            final int keyToModify = random.nextInt(maxKey);
            final ModifiableValue<FCInt> modifiableValue = map.getForModify(keyToModify);
            if (modifiableValue != null) {
                modifiableValue.value().setValue(random.nextInt());
            }

            // Every Nth round make a copy
            if (iteration % operationsPerCopy == 0) {
                copies.copy();
                // If there are too many copies then delete one at random
                if (copies.getNumberOfCopies() > copiesToKeep) {
                    copies.delete(random);
                }
            }

            // Every Nth round do validation
            if (iteration % operationsPerValidation == 0) {
                assertValidity(copies);
            }
        }

        System.out.println("Random operations finished, deleting all remaining copies and validating");

        assertValidity(copies);

        while (copies.getNumberOfCopies() > 1) {
            copies.delete(random);
            assertValidity(copies);
        }

        copies.deleteMutableCopy();
    }
}
