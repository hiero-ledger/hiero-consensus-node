// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import com.swirlds.common.test.fixtures.io.SerializationUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the FCQueue methods, which indirectly tests FCQueueNode and FCQueueIterator methods.
 */
@DisplayName("FCQueue Tests")
class FCQueueTest {

    /** the bounds to use with each {@link Random#nextInt(int)} call */
    private static final int NEXT_INT_BOUNDS = 10_000;

    /** the random number generator for both the contents of the FCInt objects, and the sequence of operations */
    private final Random rnd = new Random();

    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.fcqueue");
        registry.registerConstructables("com.swirlds.common.merkle");
        registry.registerConstructables("com.swirlds.common.test.fixtures.fcqueue");
    }

    /**
     * A simple test of adding and removing elements, while printing them all to the console.
     */
    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Console Test")
    void consoleTest() {
        rnd.setSeed(0); // get repeatable results by always using the same seed

        // should some outputs be printed to the console?
        final boolean PRINT_TO_CONSOLE = true;
        if (PRINT_TO_CONSOLE) { // put 5 elements into a queue, then pull them back out, and check the order
            final FCQueue<FCInt> q = new FCQueue<>();
            final FCInt[] d = new FCInt[5];
            for (int i = 0; i < d.length; i++) {
                d[i] = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
                assertEquals(i, q.size());
                q.add(d[i]);
            }
            assertEquals(d.length, q.size());
            for (int i = 0; i < d.length; i++) {
                if (PRINT_TO_CONSOLE) {
                    System.out.println("d[" + i + "].getValue() = " + d[i].getValue());
                }
            }
            if (PRINT_TO_CONSOLE) {
                System.out.println("=================");
            }
            for (final Object e : q) {
                if (PRINT_TO_CONSOLE) {
                    System.out.println("q iterate = " + ((FCInt) e).getValue());
                }
            }
            if (PRINT_TO_CONSOLE) {
                System.out.println("-----------------");
            }
            for (int i = 0; i < d.length; i++) {
                final int x = (q.remove()).getValue();
                if (PRINT_TO_CONSOLE) {
                    System.out.println("removed i = " + i + " returning " + x);
                }
                Assertions.assertEquals(d[i].getValue(), x);
                assertEquals(d.length - i - 1, q.size());
            }
        }
    }

    /**
     * A simple hash test: add then remove two elements, and ensure the result is still zeros.
     *
     * These FCQueue methods are tested:
     *
     * getHash()
     */
    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Hash Test 1")
    public void hashTest1() {
        // get repeatable results by always using the same seed
        rnd.setSeed(0);

        final FCQueue<FCInt> q3 = new FCQueue<>();
        q3.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q3.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q3.remove();
        q3.remove();
        final byte[] hh = q3.getHash().copyToByteArray();
        // the hash of {} should be all zeros
        for (final byte b : hh) {
            assertEquals(0, b);
        }
    }

    /**
     * A simple test that order does affect the hash.  This should fail for sum hash, but pass for rolling and Merkle
     * hash
     *
     * These FCQueue methods are tested:
     *
     * getHash()
     */
    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Hash Test 2")
    public void hashTest2() {
        /* queue to hold {e1,e2} */
        final FCQueue<FCInt> q1 = new FCQueue<>();

        /* queue to hold {e2,e1} */
        final FCQueue<FCInt> q2 = new FCQueue<>();

        /* random element */
        final FCInt e1;

        /* another random element */
        final FCInt e2;

        // get repeatable results by always using the same seed
        rnd.setSeed(0);

        e1 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        e2 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        q1.add(e1);
        q1.add(e2);
        q2.add(e2);
        q2.add(e1);

        // if FCQueue.hashAlg == 0 (sum hash), then delete the "Not" on the following line
        assertNotEquals(q1.getHash(), q2.getHash());
    }

    /**
     * Add {a,b,c,a,b,c} then remove them all and verify that both {a,b,c} lists and both {} lists have the same hash.
     * Also check that the other hashes are different. And that the {} hash is all zeros.
     *
     * These FCQueue methods are tested:
     *
     * getHash()
     */
    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Hash Test 3")
    public void hashTest3() {
        /* the queue that grows then shrinks */
        final FCQueue<FCInt> queue = new FCQueue<>();

        /* the hashes of the 13 lists in the order {}, {a}, {a,b}, ... {a,b,c,a,b,c}, {a,b,c,a,b} ... {} */
        final Hash[] hash = new Hash[13];

        /* the 3 elements to insert a,b,c */
        final FCInt[] element = new FCInt[3];

        /* which list to hash next */
        int h = 0;

        // get repeatable results by always using the same seed
        rnd.setSeed(0);

        // let a,b,c be 3 random FCInt objects
        for (int i = 0; i < element.length; i++) {
            element[i] = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        }

        // 0={}
        hash[h] = queue.getHash();
        h++;

        // the hash of {} should be all zeros
        for (int i = 0; i < hash[0].getBytes().length(); i++) {
            assertEquals(0, hash[0].getBytes().getByte(i));
        }

        // 1={a} 2={a,b} 3={a,b,c}
        for (final FCInt fcInt : element) {
            queue.add(fcInt);
            hash[h] = queue.getHash();
            h++;
        }

        // 4={a,b,c,a} 5={a,b,c,a,b} 6={a,b,c,a,b,c}
        for (final FCInt fcInt : element) {
            queue.add(fcInt);
            hash[h] = queue.getHash();
            h++;
        }

        // 7={b,c,a,b,c} 8={c,a,b,c} 9={a,b,c}
        for (final FCInt fcInt : element) {
            final FCInt elm = queue.remove();
            Assertions.assertEquals(elm, fcInt);
            hash[h] = queue.getHash();
            h++;
        }

        // 10={b,c} 11={c} 12={}
        for (final FCInt fcInt : element) {
            final FCInt elm = queue.remove();
            Assertions.assertEquals(elm, fcInt);
            hash[h] = queue.getHash();
            h++;
        }

        assertTrue(queue.isEmpty());

        // compare every pair of hashes, and ensure they are equal/unequal as appropriate
        for (int i = 0; i < 12; i++) {
            for (int j = i + 1; j < 13; j++) {
                if ((i == 0 && j == 12) || (i == 3 && j == 9)) {
                    // should have hash({})==hash({}) and hash({a,b,c})==hash({a,b,c})
                    assertEquals(hash[i], hash[j]);
                } else {
                    assertNotEquals(hash[i], hash[j]);
                }
            }
        }
    }

    /**
     * Checks if the hash matches when the FCQ is serialized and deserialized
     *
     * @throws Exception
     * 		if something goes wrong
     */
    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Hash Test 4")
    public void hashTest4() throws Exception {
        // get repeatable results by always using the same seed
        rnd.setSeed(0);

        final FCQueue<FCInt> q = new FCQueue<>();
        q.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q.remove();

        final FCQueue<FCInt> qc = SerializationUtils.serializeDeserialize(q);

        assertEquals(q.getHash(), qc.getHash());
    }

    /**
     * This test fails if the null hash for the FCQ is not all zeros. It was added when there was an attempt to change
     * that.
     */
    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Hash Test 5")
    public void hashTest5() {
        final FCQueue<FCInt> q1 = new FCQueue<>();
        final FCQueue<FCInt> q2 = new FCQueue<>();

        q1.add(new FCInt(123));
        q1.add(new FCInt(123));
        q1.remove();

        q2.add(new FCInt(123));

        assertEquals(q1.getHash(), q2.getHash());
    }

    /**
     * Create a FCQueue, add elements to it.
     * Do the identical operations on a LinkedList object, and verify that the results are exactly the same
     */
    @ParameterizedTest
    @ValueSource(ints = {1_000, 10_000})
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Add Test")
    public void addTest(final int targetSize) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final Queue<FCInt> queue = new LinkedList<>();
        addElements(fcq, queue, targetSize);
        assertEquals(targetSize, fcq.size());
        assertTrue(elementsEquals(fcq, queue));
    }

    /**
     * Create a FCQueue instance and a Queue instance, add the same elements to them.
     * remove the same number of elements from them, and verify that the results are exactly the same
     */
    @ParameterizedTest
    @CsvSource({"1000, 0.2", "10000, 0.5", "10000, 0.8", "10000, 1.0"})
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Remove Test")
    public void removeTest(final int size, final double removeRatio) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final Queue<FCInt> queue = new LinkedList<>();
        addElements(fcq, queue, size);

        // FCQueue doesn't support remove an element from the middle of the queue
        assertThrows(UnsupportedOperationException.class, () -> fcq.remove(new FCInt(0)));
        // FCQueue doesn't support removeAll
        assertThrows(UnsupportedOperationException.class, () -> fcq.removeAll(Collections.singleton(new FCInt(0))));

        int removeNum = (int) (size * removeRatio);
        while (removeNum > 0) {
            Assertions.assertEquals(queue.remove(), fcq.remove());
            removeNum--;
        }
        assertTrue(elementsEquals(fcq, queue));
    }

    /**
     * Create a FCQueue, add elements to it.
     * Creates a copy of it, the copy should be mutable and the original FCQueue should be immutable
     * Elements in both FCQueues should be the same
     */
    @ParameterizedTest
    @ValueSource(ints = {1_000, 10_000})
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Copy Test")
    public void copyTest(final int targetSize) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        addElements(fcq, null, targetSize);
        final FCQueue<FCInt> mutableCopy = fcq.copy();
        // elements in the original and the copy should be equal
        assertTrue(elementsEquals(fcq, mutableCopy));
        // this copy is mutable
        assertFalse(mutableCopy.isImmutable());
        mutableCopy.add(new FCInt(0));
        // original is immutable
        assertTrue(fcq.isImmutable());
        final Exception exception = assertThrows(IllegalStateException.class, () -> fcq.add(new FCInt(0)));
        assertEquals("tried to modify an immutable FCQueue", exception.getMessage());
    }

    /**
     * Create a FCQueue, add elements to it;
     * Creates a copy of it;
     * Clear the original FCQueue, its size should be 0;
     * the copy's size should be the original size
     */
    @ParameterizedTest
    @ValueSource(ints = {1_000, 10_000})
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Release Test")
    void releaseTest(final int targetSize) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        addElements(fcq, null, targetSize);
        assertEquals(targetSize, fcq.size());
        assertTrue(fcq.iterator().hasNext());

        final FCQueue<FCInt> copy = fcq.copy();

        fcq.release();
        assertEquals(0, fcq.size());
        // its iterator should not has next element
        assertFalse(fcq.iterator().hasNext());

        // clear original FCQueue doesn't affect the copy
        assertEquals(targetSize, copy.size());
        // the copy's iterator should has next element
        assertTrue(copy.iterator().hasNext());
    }

    /**
     * Create a FCQueue, add elements to it.
     * Do the identical operations on a LinkedList object, and verify that the results are exactly the same
     */
    @ParameterizedTest
    @ValueSource(ints = {1_000, 10_000})
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("toArray Test")
    public void toArrayTest(final int targetSize) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final Queue<FCInt> queue = new LinkedList<>();
        addElements(fcq, queue, targetSize);

        final Object[] fcqArray = fcq.toArray();
        final Object[] queueArray = queue.toArray();
        assertEquals(targetSize, fcqArray.length);
        assertEquals(queueArray.length, fcqArray.length);
        for (int i = 0; i < targetSize; i++) {
            assertEquals(fcqArray[i], queueArray[i]);
        }
    }

    /**
     * add the same element to a FCQueue instance and a Queue instance
     * if queue is null, we only add elements into the FCQueue instance
     *
     * @param fcq
     * 		a FCQueue instance
     * @param queue
     * 		a Queue instance
     * @param targetSize
     * 		target size of the two instances
     */
    void addElements(final FCQueue<FCInt> fcq, final Queue<FCInt> queue, final int targetSize) {
        for (int i = 0; i < targetSize; i++) {
            final FCInt fcInt = new FCInt(rnd.nextInt());
            fcq.add(fcInt);
            if (queue != null) {
                queue.add(fcInt);
            }
        }
    }

    /**
     * @return whether the elements in a FCQueue instance and a Queue instance are the same
     */
    static boolean elementsEquals(final FCQueue<FCInt> fcq, final Queue<FCInt> queue) {
        if (fcq.size() != queue.size()) {
            System.out.println("fcq and queue have different size");
            return false;
        }
        final Iterator<FCInt> fcqIterator = fcq.iterator();
        final Iterator<FCInt> queueIterator = queue.iterator();
        while (fcqIterator.hasNext() && queueIterator.hasNext()) {
            final FCInt fcqEle = fcqIterator.next();
            final FCInt queueEle = queueIterator.next();
            if (!fcqEle.equals(queueEle)) {
                System.out.println("fcq and queue have different element");
                return false;
            }
        }
        if (fcqIterator.hasNext() || queueIterator.hasNext()) {
            System.out.println(
                    "fcq and queue have different size, either FCQueue#size method or FCQueueIterator doesn't work "
                            + "properly");
            return false;
        }
        return true;
    }

    /**
     * Create queues both by doing a "new" and a "copy", and add and remove elements from them.
     * Do the identical operations on LinkedList objects, and verify that the results are exactly the same.
     *
     * These FCQueue methods are tested:
     *
     * add(o)
     * remove()
     * copy()
     * clear()
     * delete()
     * size()
     * toArray()
     * toArray(array)
     * iterator: for (E e : queue)
     */
    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Add Remove Copy Test")
    public void addRemoveCopyTest() {
        // number of original queues (not copies) to maintain
        final int numOrig = 100;

        // number of queue copies (not originals) to maintain
        final int numCopies = 500;

        /* original FCQueue objects (not copies) */
        final List<FCQueue<FCInt>> origFCQ = new ArrayList<>();

        /* original LinkedList objects (not copies) */
        final List<LinkedList<FCInt>> origL = new ArrayList<>();

        /* copies of FCQueue objects (not originals) */
        final List<FCQueue<FCInt>> copyFCQ = new ArrayList<>(numOrig);

        /* copies of LinkedList objects (not originals) */
        final List<LinkedList<FCInt>> copyL = new ArrayList<>();

        // the hash of each copy. It is periodically checked that it still matches that queue's getHash().
        final List<Hash> copyHash = new ArrayList<>();

        rnd.setSeed(0); // get repeatable results by always using the same seed

        // initialize all 4 arrays with empty queues and copies of them
        for (int i = 0; i < numOrig; i++) {
            origFCQ.add(new FCQueue<>());
            origL.add(new LinkedList<>());
        }

        for (int index = 0; index < numCopies; index++) {
            copyL.add(null);
            copyFCQ.add(null);
            copyHash.add(null);
        }

        for (int i = 0; i < numCopies; i++) {
            final int source = (i + 1) % numOrig;
            copyFCQ.set(
                    i,
                    origFCQ.get(source).isImmutable()
                            ? origFCQ.get(source)
                            : origFCQ.get(source).copy());
            copyL.set(i, new LinkedList<>());
            assertTrue(origFCQ.get(source).isImmutable(), "A copy was created on this object.");
        }

        // run numTestAddRemoveCopy batches, each of which does copy/delete and add/remove operations
        // number of times to run the batch of add/remove and copy/delete operations
        // a million takes 142 seconds
        final int numBatches = 10_000;
        for (int batch = 0; batch < numBatches; batch++) {

            // randomly add or remove (equal probability) an element from a random copied queue, repeatedly
            // number of add/remove operations per batch
            final int numAddRemovePerBatch = 100;
            for (int i = 0; i < numAddRemovePerBatch; i++) {
                final int k = rnd.nextInt(numCopies);
                if (rnd.nextInt(2) == 0) {
                    if (copyFCQ.get(k).isImmutable()) {
                        continue;
                    }

                    final FCInt fci = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
                    copyFCQ.get(k).add(fci);
                    copyL.get(k).add(fci);
                    assertEquals(copyFCQ.get(k).size(), copyL.get(k).size());
                } else if (copyFCQ.get(k).size() > 0 && !copyFCQ.get(k).isImmutable()) {
                    final int eFCQ = copyFCQ.get(k).remove().getValue();
                    final int eL = copyL.get(k).remove().getValue();
                    assertEquals(eFCQ, eL);
                }
            }

            // check that all those operations on the originals caused no changes in the copies, or their hashes
            final Hash defaultHash = new Hash();
            for (int i = 0; i < numOrig; i++) {
                assertArrayEquals(listToInts(origL.get(i)), qToInts(origFCQ.get(i)));
                assertEquals(defaultHash, origFCQ.get(i).getHash());
                assertEquals(origFCQ.get(i).size(), origL.get(i).size());
            }

            // delete or clear some originals and replace with new ones
            // number of original delete and replace operations per batch
            final int numDeletePerBatch = 10;
            for (int i = 0; i < numDeletePerBatch; i++) {
                final int k = rnd.nextInt(numOrig);
                if (!origFCQ.get(k).isDestroyed()) {
                    origFCQ.get(k).release();
                }
                if (rnd.nextInt(2) == 0) { // flip a coin to either replace or clear the queue
                    origFCQ.set(k, new FCQueue<>()); // replace with a new original
                }

                origL.set(k, new LinkedList<>());

                assertTrue(origFCQ.get(k).isEmpty(), "No new elements added yet");
            }

            // delete some copies and replace with new copies
            // number of copy operations per batch (each of which deletes an old copy)
            final int numCopyPerBatch = 10;
            for (int i = 0; i < numCopyPerBatch; i++) {
                final int src = rnd.nextInt(numOrig);
                final int dst = rnd.nextInt(numCopies);
                if (!copyFCQ.get(dst).isDestroyed()) {
                    copyFCQ.get(dst).release();
                }
                copyFCQ.set(
                        dst,
                        origFCQ.get(src).isImmutable()
                                ? origFCQ.get(src)
                                : origFCQ.get(src).copy());
                copyHash.set(dst, origFCQ.get(src).getHash());
                copyL.set(dst, (LinkedList<FCInt>) origL.get(src).clone());
                assertArrayEquals(
                        listToInts(copyL.get(dst)), qToInts(copyFCQ.get(dst)), "Contents of queues should match");
                assertTrue(origFCQ.get(src).isImmutable(), "A copy has already been created");
            }
        }
    }

    /**
     * Creates a new queue of the {@code numElements} length and serializes it into bytes and subsequently attempts to
     * recover the original queue from those bytes. The test checks that the recovered queue matches the original queue
     * in both contents and order. Also tests that the queue's equals and hashCode methods are implemented correctly.
     *
     * @param numElements
     * 		the number of elements in the original FCQ
     * @throws IOException
     * 		if an error occurs during serialization, indicates a test failure
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 100, 1000})
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Serialize And Recover Test")
    public void serializeAndRecoverTest(final int numElements) throws IOException {
        /* original FCQueue objects (not copies) */
        final FCQueue<FCInt> origFCQ = new FCQueue<>();

        /* copy of the original FCQueue recovered through serialization */
        final FCQueue<FCInt> recoveredFCQ;

        // Build up the original FCQueue
        for (int i = 0; i < numElements; i++) {
            origFCQ.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        }

        // Serialize the original FCQueue
        final byte[] serializedQueue;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                final SerializableDataOutputStream dos = new SerializableDataOutputStream(bos)) {
            dos.writeSerializable(origFCQ, true);
            dos.flush();
            serializedQueue = bos.toByteArray();
        }

        assertNotNull(serializedQueue);
        assertTrue(serializedQueue.length > 0);

        // Recover the serialized FCQueue into the recoveredFCQ variable
        try (final ByteArrayInputStream bis = new ByteArrayInputStream(serializedQueue);
                final SerializableDataInputStream dis = new SerializableDataInputStream(bis)) {
            recoveredFCQ = dis.readSerializable();
        }

        assertNotNull(recoveredFCQ);
        assertEquals(origFCQ.size(), recoveredFCQ.size());

        // Assert that the queues are identical in content and order
        assertArrayEquals(qToInts(origFCQ), qToInts(recoveredFCQ));

        // Assert that the queues are equal based on Object::equals
        assertEquals(origFCQ, recoveredFCQ);

        // Assert that both have the same Object::hashCode
        assertEquals(origFCQ.hashCode(), recoveredFCQ.hashCode());
    }

    /**
     * This test deserializes a queue from `serialized_queue_v2_*` files which were created
     * using MIGRATE_TO_SERIALIZABLE version of FCQueue class. Also, this tests uses `serialized_nums_*` files to
     * verify the content of the deserialized queue. The idea of the test is to verify backward compatibility of deserialization.
     *
     * @param numElements the number of elements in the original FCQ
     * @throws IOException            if an error occurs during serialization, indicates a test failure
     * @throws ClassNotFoundException shouldn't happen really
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 100, 1000})
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Serialization compatibility Test")
    public void serializationCompatibilityTest(final int numElements) throws IOException, ClassNotFoundException {
        final String queueFileName = String.format("/serialization_compatibility/serialized_queue_v2_%s", numElements);
        final String numbersFilename = String.format("/serialization_compatibility/serialized_nums_%s", numElements);
        final int[] numbers;
        final FCQueue<FCInt> recoveredFCQ;

        // Recover the serialized FCQueue into the recoveredFCQ variable
        try (final ByteArrayInputStream bis = new ByteArrayInputStream(
                        getClass().getResourceAsStream(queueFileName).readAllBytes());
                final ObjectInputStream oin = new ObjectInputStream(getClass().getResourceAsStream(numbersFilename));
                final SerializableDataInputStream dis = new SerializableDataInputStream(bis)) {
            recoveredFCQ = dis.readSerializable();
            numbers = (int[]) oin.readObject();
        }

        assertNotNull(recoveredFCQ);

        assertEquals(numElements, recoveredFCQ.size());

        // Assert that the queues are identical in content and order
        assertArrayEquals(numbers, qToInts(recoveredFCQ));
    }

    /**
     * Return an array of all the int values in a queue of FCInt objects.
     *
     * Also, check that toArray() toArray(a) give the same answers as using the iterator,
     * when a.length is less than, equal, and greater than the right size.
     *
     * @param queue
     * 		the queue of FCInt objects
     * @return an array of the values in those objects, in order from head to tail
     */
    private int[] qToInts(final FCQueue<FCInt> queue) {
        final Object[] elements1 = queue.toArray();
        final FCInt[] elements2 = queue.toArray(new FCInt[queue.size()]); // right size
        final FCInt[] elements3 = queue.toArray(new FCInt[Math.max(0, queue.size())]); // too small
        final FCInt[] elements4 = new FCInt[queue.size() + 5]; // too large

        elements4[queue.size()] = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)); // this should be set to null by toArray
        // (array)
        queue.toArray(elements4);

        final int[] values = new int[queue.size()];
        int i = 0;
        for (final FCInt e : queue) {
            values[i] = e.getValue();
            i++;
        }
        assertEquals(i, queue.size()); // the iterator should return exactly size() elements

        // check that toArray() and toArray(array) give the same answer as iterating through it
        assertEquals(elements1.length, values.length);
        for (int j = 0; j < elements1.length; j++) {
            Assertions.assertEquals(((FCInt) elements1[j]).getValue(), values[j]);
            Assertions.assertEquals((elements1[j]), elements2[j]);
            Assertions.assertEquals((elements1[j]), elements3[j]);
            Assertions.assertEquals((elements1[j]), elements4[j]);
            elements1[j] = null;
        }

        assertNull(elements4[queue.size()]); // check that toArray(t[]) puts a null after the data
        return values;
    }

    /**
     * return an array of all the int values in a queue of FCInt objects
     *
     * @param list
     * 		the LinkedList of FCInt objects
     * @return an array of the values in those objects, in order from head to tail
     */
    private int[] listToInts(final LinkedList<FCInt> list) {
        final int[] values = new int[list.size()];
        int i = 0;

        for (final FCInt e : list) {
            values[i++] = e.getValue();
        }

        return values;
    }

    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Second Mute Copy Should Fail Test")
    public void secondMutateCopyShouldFailTest() {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final int numElements = 10;
        for (int index = 0; index < numElements; index++) {
            fcq.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        }

        // make one mutable copy; original fcq becomes immutable
        final FCQueue<FCInt> copy = fcq.copy();
        copy.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));

        // should fail when making a mutable copy from fcq
        final Exception exception = assertThrows(IllegalStateException.class, fcq::copy);
        assertEquals("Tried to make a copy of an immutable FCQueue", exception.getMessage());
        assertFalse(copy.isImmutable());
        assertTrue(fcq.isImmutable());
    }

    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Serialization and deserialization of FCQueue with zero elements")
    public void validateSerializeDeserializeWithZeroElements() {
        try {
            final FCQueue<FCInt> fcq = new FCQueue<>();
            assertEquals(fcq.size(), 0);

            final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            final SerializableDataOutputStream outputStream = new SerializableDataOutputStream(outStream);

            outputStream.writeSerializableIterableWithSize(Collections.emptyIterator(), 0, true, false);

            final ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
            final SerializableDataInputStream inputStream = new SerializableDataInputStream(inStream);
            inputStream.readSerializableIterableWithSize(10, fcq::add);
        } catch (final Exception ex) {
            // should not fail with EOFException
            assertFalse(ex instanceof java.io.EOFException);
            ex.printStackTrace();
        }
    }

    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Adding empty list doesn't change the internals of FCQueue")
    public void noChangesWhenAddingEmpty() {
        final FCQueue<FCInt> fcqueue = new FCQueue<>();
        fcqueue.add(new FCInt(100));

        final Iterator<FCInt> iterator = fcqueue.iterator();
        fcqueue.addAll(Collections.emptyList());
        if (iterator.hasNext()) {
            Assertions.assertEquals(100, iterator.next().getValue());
        } else {
            fail("Iterator should have one element");
        }
    }

    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Adding empty list doesn't change the internals of FCQueue")
    public void noChangesWhenFailingToAdd() {
        final FCQueue<FCInt> fcqueue = new FCQueue<>();
        fcqueue.add(new FCInt(100));
        final FCQueue<FCInt> mutableFcq = fcqueue.copy();

        final Iterator<FCInt> iterator = fcqueue.iterator();
        final Exception exception =
                assertThrows(IllegalStateException.class, () -> fcqueue.addAll(Collections.singleton(new FCInt(200))));

        if (iterator.hasNext()) {
            Assertions.assertEquals(100, iterator.next().getValue());
        } else {
            fail("Iterator should have one element");
        }

        assertEquals("tried to modify an immutable FCQueue", exception.getMessage());
    }

    /**
     * Initializes and adds elements to original FCQueue;
     * Creates a copy of original FCQueue;
     * Starts a given number of threads which read on the original FCQueue;
     * Starts a thread which writes on the copy
     * Verifies that the writes on the copy doesn't affect the original FCQueue
     *
     * @param readThreadsNum
     * 		the number of threads read on the original FCQueue
     */
    @ParameterizedTest
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Multiple threads operate on FCQueues: multiple threads reads on the original, "
            + "one thread writes on the copy")
    @ValueSource(ints = {5, 10, 200})
    public void multipleReadThreadsAndOneWriteThreadTest(final int readThreadsNum) {
        final FCQueue<FCInt> original = new FCQueue<>();
        final int targetOriginalSize = 10000;
        for (int i = 0; i < targetOriginalSize; i++) {
            original.add(new FCInt(i));
        }

        final FCQueue<FCInt> copy = original.copy();

        final List<Thread> readThreads = new ArrayList<>();
        // create threads for reading on the original FCQueue
        for (int j = 0; j < readThreadsNum; j++) {
            final Thread readThread = new Thread(() -> {
                final Iterator<FCInt> iterator = original.iterator();
                // the elements of original FCQueue still exist when writeThread remove them from the copy
                for (int i = 0; i < targetOriginalSize; i++) {
                    Assertions.assertEquals(i, iterator.next().getValue());
                }
                // original fcqueue should not contain new elements added into the copy by writeThread
                for (int i = targetOriginalSize; i < targetOriginalSize * 2; i++) {
                    assertFalse(original.contains(new FCInt(i)));
                }
            });
            readThreads.add(readThread);
        }

        // create a thread for writing on the FCQueue copy
        final Thread writeThread = new Thread(() -> {
            // remove the elements contained in original FCQueue from the copy
            for (int i = 0; i < targetOriginalSize; i++) {
                copy.remove();
            }
            // add new elements to the copy
            for (int i = targetOriginalSize; i < targetOriginalSize * 2; i++) {
                copy.add(new FCInt(i));
            }
        });

        for (final Thread readThread : readThreads) {
            readThread.start();
        }
        writeThread.start();
    }

    /**
     * Creates a given number of threads which write on the same FCQueue
     * Verifies that none of the writes is lost
     *
     * @param writeThreadsNum
     * 		the number of threads write on the original FCQueue
     */
    @ParameterizedTest
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Multiple threads write on a FCQueue")
    @ValueSource(ints = {5, 10, 200})
    void multipleWriteThreadsTest(final int writeThreadsNum) {
        final FCQueue<FCInt> original = new FCQueue<>();
        final int targetSize = 10000;
        final int targetSizePerThread = targetSize / writeThreadsNum;

        final List<Thread> writeThreads = new ArrayList<>();
        // create threads for writing on the original FCQueue
        for (int j = 0; j < writeThreadsNum; j++) {
            final Thread writeThread = new Thread(() -> {
                for (int i = 0; i < targetSizePerThread; i++) {
                    original.add(new FCInt(i));
                }
            });
            writeThreads.add(writeThread);
        }
        // start all write threads
        for (final Thread writeThread : writeThreads) {
            writeThread.start();
        }
        // wait for all write threads finish
        for (final Thread writeThread : writeThreads) {
            try {
                writeThread.join();
            } catch (final InterruptedException ex) {
                System.out.println("writeThread is interrupted");
            }
        }

        final Iterator<FCInt> iterator = original.iterator();
        final int[] counts = new int[targetSizePerThread];
        while (iterator.hasNext()) {
            counts[iterator.next().getValue()]++;
        }
        // each number in counts should be equal to writeThreadNum
        for (final int count : counts) {
            assertEquals(writeThreadsNum, count);
        }
    }

    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Adding an element when a FCQueue has reached MAX_ELEMENTS")
    void addWhenReachMaxLimit() {
        final AtomicInteger maxElements = new AtomicInteger(FCQueue.MAX_ELEMENTS - 1);
        final FCQueue<FCInt> fcqueue = new FCQueue<>() {
            @Override
            public int size() {
                return maxElements.getAndIncrement();
            }
        };

        fcqueue.add(new FCInt(100));
        final Exception exception = assertThrows(IllegalStateException.class, () -> fcqueue.add(new FCInt(200)));
        assertTrue(exception
                .getMessage()
                .contains("tried to add an element to an FCQueue whose size has reached MAX_ELEMENTS"));
    }

    @Test
    @Tag(TestComponentTags.FCQUEUE)
    @DisplayName("Clear is invalid on a immutable copy")
    void clearOnCopy() {
        final FCQueue<FCInt> queue = new FCQueue<>();
        for (int index = 0; index < 5; index++) {
            queue.add(new FCInt(index));
        }

        final FCQueue<FCInt> mutableQueue = queue.copy();

        final Exception exception =
                assertThrows(MutabilityException.class, queue::clear, "Calling mutating method on an immutable object");
        assertEquals(
                "This operation is not permitted on an immutable object.",
                exception.getMessage(),
                "The message should be about mutating an immutable object");

        mutableQueue.clear();
        assertTrue(mutableQueue.isEmpty(), "Queue should be empty after clear");
    }

    /**
     * Test migration.
     *
     * This test requires maintenance every time a new major release is made.
     *
     * 1) Checkout the release.
     * 2) Ensure that the "previousVersion" variable is properly set.
     * 3) Uncomment the line that saves the object. Run the test. A new file will be generated.
     * 4) Re-comment the line that saves the object.
     * 5) Remove the old file.
     */
    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Migration Test")
    void migrationTest() throws Exception {

        final FCQueue<FCInt> generated = new FCQueue<>();
        for (int element = 0; element < 10_000; element++) {
            generated.add(new FCInt(element));
        }

        // Update this version when updating the saved file.
        final String previousVersion = "0.7.3";

        final String fileName = "FCQueue-" + previousVersion + ".dat";

        // Uncomment this block of code to write a new address book to disk.
        // Do not commit this file to git with this block uncommented.
        //		FileOutputStream fOut = new FileOutputStream("src/test/resources/" + fileName);
        //		SerializableDataOutputStream out = new SerializableDataOutputStream(fOut);
        //		out.writeSerializable(generated, true);
        //		System.out.println("Don't forget to comment this block out before committing");

        final InputStream fIn = getClass().getClassLoader().getResourceAsStream(fileName);
        final SerializableDataInputStream in = new SerializableDataInputStream(fIn);

        final FCQueue<FCInt> deserialized = in.readSerializable();

        assertEquals(generated, deserialized, "Deserialized object does not match generated object");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    void invalidateHash() {
        final FCQueue<FCInt> queue = new FCQueue<>();
        for (int index = 0; index < 5; index++) {
            queue.add(new FCInt(index));
        }

        final Hash hash = queue.getHash();
        assertNotNull(hash, "FCQueue computes its own hash");

        queue.invalidateHash();

        final Hash computedHash = queue.getHash();
        assertNotNull(computedHash, "invalidateHash doesn't affect SelfHashable objects");
        assertEquals(hash, computedHash, "The value of the hashes must match");
    }

    @Test
    @DisplayName("Reverse Iterator Test Test")
    void reverseIteratorTest() {
        final FCQueue<FCInt> queue = new FCQueue<>();
        for (int index = 0; index < 100; index++) {
            queue.add(new FCInt(index));
        }

        Iterator<FCInt> iterator = queue.reverseIterator();
        for (int index = 99; index >= 0; index--) {
            Assertions.assertEquals(new FCInt(index), iterator.next(), "value should match");
        }
        assertFalse(iterator.hasNext(), "iterator should be depleted");

        // Making a copy and updating the copy shouldn't affect original copy's iterator

        final FCQueue<FCInt> copy = queue.copy();
        copy.remove();
        copy.add(new FCInt(123456));

        iterator = queue.reverseIterator();
        for (int index = 99; index >= 0; index--) {
            Assertions.assertEquals(new FCInt(index), iterator.next(), "value should match");
        }
        assertFalse(iterator.hasNext(), "iterator should be depleted");
    }
}
