// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImmutableIndexedObjectListUsingArrayTest {

    private static ImmutableIndexedObjectList<TestIndexedObject> list;

    protected ImmutableIndexedObjectList<TestIndexedObject> factoryForReaderToTest(final TestIndexedObject[] objects) {
        return new ImmutableIndexedObjectListUsingArray<>(TestIndexedObject[]::new, Arrays.asList(objects));
    }

    @Test
    @Order(1)
    void createData0_100() {
        list = factoryForReaderToTest(
                IntStream.range(0, 100).mapToObj(TestIndexedObject::new).toArray(TestIndexedObject[]::new));
        checkRange(99);
    }

    @Test
    @Order(2)
    void add100() {
        final TestIndexedObject object100 = new TestIndexedObject(100);
        list = list.withAddedObject(object100);
        checkRange(100);
        assertSame(object100, list.getLast(), "The getLast() method should return th elast object added");
    }

    @Test
    @Order(3)
    void removeSome() {
        list = list.withDeletedObjects(Set.of(list.get(10), list.get(20), list.get(30)));
        System.out.println("list = " + list);
        assertNull(list.get(10), "Deleted object 10 should be null");
        assertNull(list.get(20), "Deleted object 20 should be null");
        assertNull(list.get(30), "Deleted object 30 should be null");
        IntStream.range(0, 9)
                .forEach(i -> assertEquals(
                        i,
                        list.get(i).getIndex(),
                        "Checking by get, expected [" + i + "] but got ["
                                + list.get(i).getIndex() + "]"));
        IntStream.range(11, 19)
                .forEach(i -> assertEquals(
                        i,
                        list.get(i).getIndex(),
                        "Checking by get, expected [" + i + "] but got ["
                                + list.get(i).getIndex() + "]"));
        IntStream.range(21, 29)
                .forEach(i -> assertEquals(
                        i,
                        list.get(i).getIndex(),
                        "Checking by get, expected [" + i + "] but got ["
                                + list.get(i).getIndex() + "]"));
        IntStream.range(31, 100)
                .forEach(i -> assertEquals(
                        i,
                        list.get(i).getIndex(),
                        "Checking by get, expected [" + i + "] but got ["
                                + list.get(i).getIndex() + "]"));
    }

    @Test
    @Order(4)
    void nullAdditionReusesList() {
        assertSame(list, list.withAddedObject(null), "Lists should reuse themselves given null additions");
    }

    @Test
    @Order(5)
    void nullInputThrows() {
        assertThrows(
                NullPointerException.class,
                () -> factoryForReaderToTest(null),
                "Factory should throw on null arguments");
    }

    @Test
    @Order(6)
    void emptyInputYieldsEmptyListWithNoopDeletion() {
        final ImmutableIndexedObjectList<TestIndexedObject> emptyList =
                factoryForReaderToTest(new TestIndexedObject[0]);

        assertNull(emptyList.getLast(), "Last element of empty list should be null");
        assertSame(
                emptyList,
                emptyList.withDeletedObjects(Collections.emptySet()),
                "Lists should reuse themselves given empty deletions");
        assertSame(
                emptyList,
                emptyList.withDeletedObjects(Set.of(new TestIndexedObject(11))),
                "Empty lists should always reuse themselves");
    }

    @Test
    @Order(7)
    void nullObjectsSkipped() {
        final TestIndexedObject object42 = new TestIndexedObject(42);
        final ImmutableIndexedObjectList<TestIndexedObject> singletonList =
                factoryForReaderToTest(new TestIndexedObject[] {null, object42, null});
        assertSame(object42, singletonList.getLast(), "Nulls should be skipped");
        assertSame(object42, singletonList.get(42), "Nulls should be skipped");
        assertNull(singletonList.get(1), "Out-of-bounds indexes have null elements");
        assertNull(singletonList.get(43), "Out-of-bounds indexes have null elements");
    }

    @Test
    @Order(8)
    void overridingAdditionWorksAsExpected() {
        final TestIndexedObject firstObject1 = new TestIndexedObject(1);
        final TestIndexedObject secondObject1 = new TestIndexedObject(1);
        final TestIndexedObject object2 = new TestIndexedObject(2);

        final ImmutableIndexedObjectList<TestIndexedObject> subject =
                factoryForReaderToTest(new TestIndexedObject[] {object2, firstObject1});

        assertSame(firstObject1, subject.get(1), "Initial subject contains first object");

        final ImmutableIndexedObjectList<TestIndexedObject> secondSubject = subject.withAddedObject(secondObject1);
        assertSame(secondObject1, secondSubject.get(1), "Updated subject contains replacement object");
    }

    @Test
    @Order(9)
    void gettersSanityCheckIndices() {
        final ImmutableIndexedObjectList<TestIndexedObject> emptyList =
                factoryForReaderToTest(new TestIndexedObject[0]);

        assertThrows(IndexOutOfBoundsException.class, () -> emptyList.get(-1), "Negative indices shouldn't be allowed");
        assertNull(emptyList.get(0), "Empty lists should always return null");
        assertNull(emptyList.get(42), "Empty lists should always return null");
    }

    private static void checkRange(int max) {
        max++; // add one to make range exclusive from inclusive
        TestIndexedObject[] objects = list.stream().toArray(TestIndexedObject[]::new);
        for (int i = 0; i < max; i++) {
            assertEquals(
                    i,
                    objects[i].getIndex(),
                    "Checking by stream, expected [" + i + "] but got [" + objects[i].getIndex() + "]");
        }
        IntStream.range(0, 100)
                .forEach(i -> assertEquals(
                        i,
                        list.get(i).getIndex(),
                        "Checking by get, expected [" + i + "] but got ["
                                + list.get(i).getIndex() + "]"));
        System.out.println("list = " + list);
    }

    /** Testing implementation of IndexedObject */
    static class TestIndexedObject implements IndexedObject {
        public final int index;

        public TestIndexedObject(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }
    }
}
