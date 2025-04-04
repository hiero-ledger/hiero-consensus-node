// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.utility.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FCOneToManyRelation Tests")
class FCOneToManyRelationTests {

    /**
     * Assert that a paginated list represents the expected subsection of the whole list.
     */
    private void assertListEquality(
            final List<Integer> referenceList,
            final List<Integer> paginatedList,
            final int startIndex,
            final int endIndex) {

        assertTrue(referenceList.size() >= endIndex, "reference list is too small");
        assertTrue(paginatedList.size() >= endIndex - startIndex, "paginated list is too small");

        for (int index = startIndex; index < endIndex; index++) {
            assertEquals(referenceList.get(index), paginatedList.get(index - startIndex), "expected values to match");
        }
    }

    /**
     * Perform sanity tests on pagination. Chooses a random key to validate.
     * <p>
     * The full list is provided as a time saving measure -- the outer context already has it so no need to regenerate.
     */
    private void validatePagination(
            final Random random, final int maxKey, final FCOneToManyRelation<Integer, Integer> relation) {

        final int key = random.nextInt(maxKey);
        final int countForKey = relation.getCount(key);

        final List<Integer> valueList = relation.getList(key);

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> relation.getList(key, -1),
                "negative start index is expected to throw exception");

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> relation.getList(key, 0, countForKey + 1),
                "too large of an end index is expected to throw exception");

        assertListEquality(valueList, relation.getList(key, 0, countForKey), 0, countForKey);

        if (countForKey >= 1) {
            assertListEquality(valueList, relation.getList(key, 1), 1, countForKey);

            assertListEquality(valueList, relation.getList(key, 0, countForKey - 1), 0, countForKey - 1);

            if (countForKey >= 2) {
                assertListEquality(valueList, relation.getList(key, 1, countForKey - 1), 1, countForKey - 1);
            }

            if (countForKey >= 3) {
                assertListEquality(valueList, relation.getList(key, 1, countForKey - 2), 1, countForKey - 2);
            }
        }

        if (countForKey >= 2) {
            assertListEquality(valueList, relation.getList(key, 2), 2, countForKey);
        }

        if (countForKey >= 3) {
            assertListEquality(valueList, relation.getList(key, 3), 3, countForKey);
        }
    }

    /**
     * Validate that a relation holds the same data as the reference structure.
     */
    private void validate(
            final Random random,
            final int maxKey,
            final Map<Integer, Set<Integer>> reference,
            final FCOneToManyRelation<Integer, Integer> relation) {

        assertEquals(reference.size(), relation.getKeyCount(), "number of keys should match");

        assertEquals(reference.keySet(), relation.getKeySet(), "expected key sets to match");

        for (final Integer key : reference.keySet()) {
            final Set<Integer> referenceValues = reference.get(key);
            final List<Integer> valueList = relation.getList(key);

            final Set<Integer> valueSet = new HashSet<>(relation.getList(key));
            assertEquals(valueList.size(), valueSet.size(), "set should be the same size as the list");
            assertEquals(valueSet.size(), relation.getCount(key), "count should match values returned");
            assertEquals(referenceValues, valueSet, "set of values should match");
        }
        validatePagination(random, maxKey, relation);
    }

    /**
     * Validate a list of reference maps and FCOneToManyRelation copies.
     */
    private void validate(
            final Random random,
            final int maxKey,
            final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> copies) {
        for (final Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>> pair : copies) {
            validate(random, maxKey, pair.left(), pair.right());
        }
    }

    /**
     * Associate a new key and value.
     */
    private void associate(
            final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> copies,
            final int key,
            final int value) {

        final Map<Integer, Set<Integer>> reference =
                copies.get(copies.size() - 1).left();
        final FCOneToManyRelation<Integer, Integer> relation =
                copies.get(copies.size() - 1).right();

        if (!reference.containsKey(key)) {
            reference.put(key, new HashSet<>());
        }
        reference.get(key).add(value);

        relation.associate(key, value);
    }

    /**
     * Disassociate a key and value.
     */
    private void disassociate(
            final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> copies,
            final int key,
            final int value) {

        final Map<Integer, Set<Integer>> reference =
                copies.get(copies.size() - 1).left();
        final FCOneToManyRelation<Integer, Integer> relation =
                copies.get(copies.size() - 1).right();

        if (reference.containsKey(key)) {
            reference.get(key).remove(value);
            if (reference.get(key).size() == 0) {
                reference.remove(key);
            }
        }

        relation.disassociate(key, value);
    }

    /**
     * Bootstrap the list of reference copies and relations. Returns size one list.
     */
    private List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> createNewPair() {
        final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> list = new ArrayList<>();
        list.add(Pair.of(new HashMap<>(), new FCOneToManyRelation<>()));
        return list;
    }

    /**
     * Make a deep copy of a reference map.
     */
    private Map<Integer, Set<Integer>> copyReferenceMap(final Map<Integer, Set<Integer>> referenceMap) {
        final Map<Integer, Set<Integer>> copy = new HashMap<>();

        for (final int key : referenceMap.keySet()) {
            final Set<Integer> setCopy = new HashSet<>(referenceMap.get(key));
            copy.put(key, setCopy);
        }

        return copy;
    }

    /**
     * Make a new copy.
     */
    private void copy(final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> copies) {

        final Map<Integer, Set<Integer>> referenceCopy =
                copyReferenceMap(copies.get(copies.size() - 1).left());
        final FCOneToManyRelation<Integer, Integer> relationCopy =
                copies.get(copies.size() - 1).right().copy();

        copies.add(Pair.of(referenceCopy, relationCopy));
    }

    /**
     * Delete a copy at a given index.
     */
    private void deleteCopy(
            final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> copies,
            final int index) {
        copies.get(index).right().release();
        copies.remove(index);
    }

    @Test
    @DisplayName("Randomized Test")
    void randomizedTest() {

        final Random random = getRandomPrintSeed();

        final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> copies = createNewPair();

        final int maxKey = 100;
        final int maxValue = 100;

        validate(random, maxKey, copies);

        for (int i = 0; i < 10_000; i++) {

            associate(copies, random.nextInt(), random.nextInt());

            if (i % 3 == 0) {
                // every third operation, disassociate
                disassociate(copies, random.nextInt(maxKey), random.nextInt(maxValue));
            }

            if (i % 100 == 0) {
                // every 100 operations make a copy
                copy(copies);
                validate(random, maxKey, copies);
            }

            if (i % 200 == 0) {
                // every 200 operations delete a copy
                deleteCopy(copies, random.nextInt(copies.size() - 1));
                validate(random, maxKey, copies);
            }
        }
        validate(random, maxKey, copies);

        while (copies.size() > 0) {
            deleteCopy(copies, copies.size() - 1);
        }
    }

    @Test
    @DisplayName("Bad Input Test")
    void badInputTest() {

        final FCOneToManyRelation<Integer, Integer> relation = new FCOneToManyRelation<>();

        assertThrows(IllegalArgumentException.class, () -> relation.associate(null, 0), "null keys are not allowed");

        assertThrows(IllegalArgumentException.class, () -> relation.associate(0, null), "null values are not allowed");

        assertThrows(IllegalArgumentException.class, () -> relation.disassociate(null, 0), "null keys are not allowed");

        assertThrows(
                IllegalArgumentException.class, () -> relation.disassociate(0, null), "null values are not allowed");

        assertThrows(
                IllegalArgumentException.class,
                () -> relation.disassociate(null, null),
                "null values and values are not allowed");

        assertThrows(IllegalArgumentException.class, () -> relation.get(null), "null keys are not allowed");

        assertThrows(IllegalArgumentException.class, () -> relation.getCount(null), "null keys are not allowed");

        assertThrows(IndexOutOfBoundsException.class, () -> relation.get(0, -1), "negative start index is not allowed");

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> relation.get(0, 5),
                "start index that exceeds size is not allowed");

        for (int i = 0; i < 10; i++) {
            relation.associate(0, i);
        }

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> relation.get(0, 5, 2),
                "end index must be greater than start index");

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> relation.get(0, 0, 100),
                "end index that exceeds size is not allowed");

        relation.release();
    }

    /**
     * This is designed to fail if garbage collection is disabled.
     */
    @Test
    @DisplayName("Garbage Collection Test")
    void garbageCollectionTest() {
        final Random random = getRandomPrintSeed();

        final int numberOfCopies = 10_000;
        final int modificationsPerCopy = 1_000;
        final int copiesToStore = 10;

        final int maxKey = 10;
        final int maxValue = 10;

        final LinkedList<FCOneToManyRelation<Integer, Integer>> copies = new LinkedList<>();
        copies.add(new FCOneToManyRelation<>());

        for (int copyNumber = 0; copyNumber < numberOfCopies; copyNumber++) {

            copies.add(copies.getLast().copy());
            if (copies.size() > copiesToStore) {
                copies.getFirst().release();
                copies.remove(0);
            }

            for (int modificationNumber = 0; modificationNumber < modificationsPerCopy; modificationNumber++) {
                copies.getLast().associate(random.nextInt(maxKey), random.nextInt(maxValue));
                copies.getLast().disassociate(random.nextInt(maxKey), random.nextInt(maxValue));
            }
        }
    }

    @Test
    @DisplayName("Remove Everything Test")
    void removeEverythingTest() {
        final Random random = getRandomPrintSeed();

        final List<Pair<Map<Integer, Set<Integer>>, FCOneToManyRelation<Integer, Integer>>> copies = createNewPair();

        final int maxKey = 1_000;
        final int maxValue = 1_000;

        validate(random, maxKey, copies);

        final Set<Pair<Integer, Integer>> associations = new HashSet<>();

        // Add a bunch of associations
        for (int i = 0; i < 10_000; i++) {

            final int key = random.nextInt(maxKey);
            final int value = random.nextInt(maxValue);

            associate(copies, key, value);
            associations.add(Pair.of(key, value));

            if (i % 100 == 0) {
                copy(copies);
                if (copies.size() > 3) {
                    deleteCopy(copies, random.nextInt(3));
                }

                validate(random, maxKey, copies);
            }
        }
        validate(random, maxKey, copies);

        // Remove all of the associations
        for (final Pair<Integer, Integer> association : associations) {

            disassociate(copies, association.key(), association.value());

            if (associations.size() % 100 == 0) {
                copy(copies);
                if (copies.size() > 3) {
                    deleteCopy(copies, random.nextInt(3));
                }

                validate(random, maxKey, copies);
            }
        }
        validate(random, maxKey, copies);

        assertEquals(0, copies.get(copies.size() - 1).right().getKeyCount(), "no entries should remain");

        while (copies.size() > 0) {
            deleteCopy(copies, copies.size() - 1);
        }
    }
}
