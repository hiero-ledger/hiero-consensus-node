// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import static org.hiero.base.utility.test.fixtures.tags.TestComponentTags.FCQUEUE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import com.swirlds.common.test.fixtures.io.SerializationUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the FCQueue methods, which indirectly tests FCQueueNode and FCQueueIterator methods.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MockFCQueue Tests")
class MockFCQueueTest {

    /** the bounds to use with each {@link Random#nextInt(int)} call */
    private static final int NEXT_INT_BOUNDS = 10_000;

    /** should some outputs be printed to the console? */
    private final boolean PRINT_TO_CONSOLE = true;

    /** number of times to run the batch of add/remove and copy/delete operations */
    private int numBatches = 10_000; // a million takes 142 seconds

    /** number of add/remove operations per batch */
    private int numAddRemovePerBatch = 100;

    /** number of original delete and replace operations per batch */
    private int numDeletePerBatch = 10;

    /** number of copy operations per batch (each of which deletes an old copy) */
    private int numCopyPerBatch = 10;

    /** number of original queues (not copies) to maintain */
    private final int numOrig = 100;

    /** number of queue copies (not originals) to maintain */
    private final int numCopies = 500;

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
    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Console Test")
    void consoleTest(final Supplier<FCQueue<FCInt>> supplier) {
        rnd.setSeed(0); // get repeatable results by always using the same seed

        if (PRINT_TO_CONSOLE) { // put 5 elements into a queue, then pull them back out, and check the order
            final FCQueue<FCInt> q = supplier.get();
            FCInt[] d = new FCInt[5];
            for (int i = 0; i < d.length; i++) {
                d[i] = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
                assertEquals(i, q.size(), "Values came out of queue in wrong order");
                q.add(d[i]);
            }
            assertEquals(d.length, q.size(), "incorrect number of elements in q");
            for (int i = 0; i < d.length; i++) {
                if (PRINT_TO_CONSOLE) {
                    System.out.println("d[" + i + "].getValue() = " + d[i].getValue());
                }
            }
            if (PRINT_TO_CONSOLE) {
                System.out.println("=================");
            }
            for (Object e : q) {
                if (PRINT_TO_CONSOLE) {
                    System.out.println("q iterate = " + ((FCInt) e).getValue());
                }
            }
            if (PRINT_TO_CONSOLE) {
                System.out.println("-----------------");
            }
            for (int i = 0; i < d.length; i++) {
                int x = (q.remove()).getValue();
                if (PRINT_TO_CONSOLE) {
                    System.out.println("removed i = " + i + " returning " + x);
                }
                Assertions.assertEquals(d[i].getValue(), x, "unexpected value removed from queue");
                assertEquals(d.length - i - 1, q.size(), "queue is the wrong length after remove");
            }
        }
    }

    /**
     * A simple hash test: add then remove two elements, and ensure the result is still zeros.
     *
     * These MockFCQueue methods are tested:
     *
     * getHash()
     */
    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Hash Test 1")
    void hashTest1(final Supplier<FCQueue<FCInt>> supplier) {
        // get repeatable results by always using the same seed
        rnd.setSeed(0);

        final FCQueue<FCInt> q3 = supplier.get();
        q3.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q3.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q3.remove();
        q3.remove();
        final byte[] hh = q3.getHash().copyToByteArray();
        // the hash of {} should be all zeros
        for (byte b : hh) {
            assertEquals(0, b, "hash for an empty queue should be 0");
        }
    }

    /**
     * A simple test that order does affect the hash.  This should fail for sum hash, but pass for rolling and Merkle
     * hash
     *
     * These MockFCQueue methods are tested:
     *
     * getHash()
     */
    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Hash Test 2")
    void hashTest2(final Supplier<FCQueue<FCInt>> supplier) {
        /* queue to hold {e1,e2} */
        final FCQueue<FCInt> q1 = supplier.get();

        /* queue to hold {e2,e1} */
        final FCQueue<FCInt> q2 = supplier.get();

        /* random element */
        FCInt e1;

        /* another random element */
        FCInt e2;

        // get repeatable results by always using the same seed
        rnd.setSeed(0);

        e1 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        e2 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        q1.add(e1);
        q1.add(e2);
        q2.add(e2);
        q2.add(e1);

        // if MockFCQueue.hashAlg == 0 (sum hash), then delete the "Not" on the following line
        assertNotEquals(q1.getHash(), q2.getHash(), "these queues should have different hashes");
    }

    /**
     * Add {a,b,c,a,b,c} then remove them all and verify that both {a,b,c} lists and both {} lists have the same hash.
     * Also check that the other hashes are different. And that the {} hash is all zeros.
     *
     * These MockFCQueue methods are tested:
     *
     * getHash()
     */
    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Hash Test 3")
    void hashTest3(final Supplier<FCQueue<FCInt>> supplier) {
        /* the queue that grows then shrinks */
        final FCQueue<FCInt> queue = supplier.get();

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
            assertEquals(0, hash[0].getBytes().getByte(i), "hash for an empty queue should be 0");
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
            Assertions.assertEquals(elm, fcInt, "wrong element removed from queue");
            hash[h] = queue.getHash();
            h++;
        }

        // 10={b,c} 11={c} 12={}
        for (final FCInt fcInt : element) {
            final FCInt elm = queue.remove();
            Assertions.assertEquals(elm, fcInt, "wrong element removed from queue");
            hash[h] = queue.getHash();
            h++;
        }

        assertTrue(queue.isEmpty(), "queue is not empty");

        // compare every pair of hashes, and ensure they are equal/unequal as appropriate
        for (int i = 0; i < 12; i++) {
            for (int j = i + 1; j < 13; j++) {
                if ((i == 0 && j == 12) || (i == 3 && j == 9)) {
                    // should have hash({})==hash({}) and hash({a,b,c})==hash({a,b,c})
                    assertEquals(hash[i], hash[j], "hash value mismatch");
                } else {
                    assertNotEquals(hash[i], hash[j], "hash values are supposed to be different");
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
    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Hash Test 4")
    void hashTest4(final Supplier<FCQueue<FCInt>> supplier) throws Exception {
        // get repeatable results by always using the same seed
        rnd.setSeed(0);

        final FCQueue<FCInt> q = supplier.get();
        q.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        q.remove();

        final FCQueue<FCInt> qc = SerializationUtils.serializeDeserialize(q);

        assertEquals(q.getHash(), qc.getHash(), "Hash value after serialization/deserialization changed");
    }

    /**
     * This test fails if the null hash for the FCQ is not all zeros. It was added when there was an attempt to change
     * that.
     */
    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Hash Test 5")
    void hashTest5(final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> q1 = supplier.get();
        final FCQueue<FCInt> q2 = supplier.get();

        q1.add(new FCInt(123));
        q1.add(new FCInt(123));
        q1.remove();

        q2.add(new FCInt(123));

        assertEquals(q1.getHash(), q2.getHash(), "Hash values are not the same");
    }

    /**
     * A simple hash test: add elements into an FCQueue and a MockFCQueue
     * to make sure that their hashes are the same when they have the same contents.
     *
     * These MockFCQueue methods are tested:
     *
     * getHash(), .add()
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("Mock Hash Test 6")
    void hashTest6(final int queueSize, final Supplier<FCQueue<FCInt>> mockSupplier) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = mockSupplier.get();

        for (int i = 0; i < queueSize; i++) {
            final FCInt elem = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
            fcq.add(elem);
            mfcq.add(elem);
        }

        final byte[] fcqh = fcq.getHash().copyToByteArray();
        final byte[] mfcqh = mfcq.getHash().copyToByteArray();

        assert (fcqh.length == mfcqh.length);
        assert (fcqh.length == 48);
        // hashes should be identical
        assertArrayEquals(mfcqh, fcqh, "FCQ and MockFCQ hashes are different");
    }

    /**
     * A simple hash test: add and remove elements into an FCQueue and a MockFCQueue
     * to make sure that their hashes are the same when they have the same contents.
     *
     * These MockFCQueue methods are tested:
     *
     * getHash(), .add()
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("Mock Hash Test 7")
    void hashTest7(final int queueSize, final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = supplier.get();

        for (int i = 0; i < queueSize; i++) {
            final FCInt elem = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
            fcq.add(elem);
            mfcq.add(elem);
        }

        assertEquals(mfcq.getHash(), fcq.getHash(), "FCQ and Mock FCQ hashes are different after adding");

        // now remove one element at a time and assure that the hashes remain the same
        for (int r = 0; r < queueSize; r++) {
            fcq.remove();
            mfcq.remove();

            // hashes should stay identical
            assertEquals(
                    mfcq.getHash(),
                    fcq.getHash(),
                    String.format("FCQ and Mock FCQ hashes are different after removal %d", r));
        }
    }

    /**
     * A simple hash test: iterative walk -- fill the queue to N, -1/2N, +1/3N, -1/4N, +1/5N
     * verifying as we go
     *
     * These MockFCQueue methods are tested:
     *
     * getHash(), .add(), .remove()
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("Mock Hash Test 8")
    void hashTest8(final int queueSize, final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = supplier.get();

        for (int stepSize = 1; stepSize <= 20; stepSize += 2) {
            // add queueSize/stepSize
            for (int i = 0; i < queueSize / stepSize; i++) {
                final FCInt elem = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
                fcq.add(elem);
                mfcq.add(elem);
            }
            // verify hashes
            final byte[] fcqh1 = fcq.getHash().copyToByteArray();
            final byte[] mfcqh1 = mfcq.getHash().copyToByteArray();
            // hashes should stay identical
            for (int i1 = 0; i1 < fcqh1.length; i1++) {
                assertEquals(fcqh1[i1], mfcqh1[i1], "FCQ and Mock FCQ hashes are different");
            }

            // remove queueSize/(stepSize+1)
            for (int r = 0; r < queueSize / (stepSize + 1); r++) {
                fcq.remove();
                mfcq.remove();
            }

            // verify hashes
            final byte[] fcqh2 = fcq.getHash().copyToByteArray();
            final byte[] mfcqh2 = mfcq.getHash().copyToByteArray();
            // hashes should stay identical
            for (int i2 = 0; i2 < fcqh2.length; i2++) {
                assertEquals(fcqh2[i2], mfcqh2[i2], "FCQ and Mock FCQ hashes are different");
            }
        }
    }

    /**
     * A mutability test: mutating same mutable copy -- two threads adding to both an
     * fcq and a mock fcq, comparing hashes afterwards
     *
     * These MockFCQueue methods are tested:
     *
     * getHash(), .add(), .copy()
     */
    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Mock Mutability Test")
    void mutabilityTest(final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = supplier.get();

        final FCInt elem1 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        final FCInt elem2 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        final FCInt elem3 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        final FCInt elem4 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        final FCInt elem5 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
        final FCInt elem6 = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));

        // create baseline queues for hashes
        final FCQueue<FCInt> fcq_baseline = new FCQueue<>();
        fcq_baseline.add(elem1);
        fcq_baseline.add(elem2);
        fcq_baseline.add(elem3);
        fcq_baseline.add(elem4);
        fcq_baseline.add(elem5);
        fcq_baseline.add(elem6);

        final FCQueue<FCInt> mfcq_baseline = supplier.get();
        mfcq_baseline.add(elem1);
        mfcq_baseline.add(elem2);
        mfcq_baseline.add(elem3);
        mfcq_baseline.add(elem4);
        mfcq_baseline.add(elem5);
        mfcq_baseline.add(elem6);

        // start with an element in each
        fcq.add(elem1);
        mfcq.add(elem1);

        final FCQueue<FCInt> fcq_dup = fcq.copy();
        final FCQueue<FCInt> mfcq_dup = mfcq.copy();

        assertEquals(mfcq.getHash(), fcq.getHash(), "Originals don't match");
        assertEquals(mfcq_dup.getHash(), fcq_dup.getHash(), "Copies don't match");

        // thread adds to the dup versions, main code adds to the original
        final Thread writeThread = new Thread(() -> {
            fcq_dup.add(elem2);
            fcq_dup.add(elem3);
            fcq_dup.add(elem4);
            fcq_dup.add(elem5);
            fcq_dup.add(elem6);
            mfcq_dup.add(elem2);
            mfcq_dup.add(elem3);
            mfcq_dup.add(elem4);
            mfcq_dup.add(elem5);
            mfcq_dup.add(elem6);
        });

        writeThread.start();
        assertDoesNotThrow((Executable) writeThread::join, "unable to join thread");

        // write to the originals -- try to modify immutable FCQueue, MockFCQueue
        assertThrows(IllegalStateException.class, () -> fcq.add(elem2), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> fcq.add(elem3), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> fcq.add(elem4), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> fcq.add(elem5), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> fcq.add(elem6), "shouldn't be able to add to queue");

        assertThrows(IllegalStateException.class, () -> mfcq.add(elem2), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> mfcq.add(elem3), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> mfcq.add(elem4), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> mfcq.add(elem5), "shouldn't be able to add to queue");
        assertThrows(IllegalStateException.class, () -> mfcq.add(elem6), "shouldn't be able to add to queue");

        // original and dup should NOT be the same size
        assertNotEquals(fcq.size(), fcq_dup.size(), "queues should be different sizes");
        assertNotEquals(mfcq.size(), mfcq_dup.size(), "queues should be different sizes");

        assertEquals(fcq_baseline.getHash(), mfcq_baseline.getHash(), "FCQ and Mock FCQ hashes are different");
        assertEquals(fcq.getHash(), mfcq.getHash(), "FCQ and Mock FCQ hashes are different");
        assertEquals(fcq_baseline.getHash(), fcq_dup.getHash(), "Baseline and copied hashes are different");
        assertEquals(mfcq_baseline.getHash(), mfcq_dup.getHash(), "Baseline and copied hashes are different");
        assertEquals(fcq_dup.getHash(), mfcq_dup.getHash(), "FCQ and Mock FCQ hashes are different");
    }

    /**
     * A multithreaded add test: adding a bunch of values in different threads
     * to test collisions.
     *
     * These MockFCQueue methods are tested:
     *
     * getHash(), .add()
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("Mock multithread Hash Test 1")
    void multithreadHashTest(int writeThreadsNum, final Supplier<FCQueue<FCInt>> supplier) {
        final int QUEUE_SIZE = 10_000;

        // make an array of source material to choose from
        final List<Integer> source = new ArrayList<>();
        for (int i = 0; i < QUEUE_SIZE; i++) {
            source.add(rnd.nextInt(NEXT_INT_BOUNDS));
        }

        // build single threaded version of queues
        final FCQueue<FCInt> fcq_single = new FCQueue<>();
        final FCQueue<FCInt> mfcq_single = supplier.get();
        for (int i = 0; i < QUEUE_SIZE; i++) {
            final FCInt elem0 = new FCInt(source.get(i));
            fcq_single.add(elem0);
            mfcq_single.add(elem0);
        }

        // build multithreaded version of queues
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = supplier.get();

        // multithreaded adds, but pulling from an array synchronously
        final List<Thread> writeThreads = new ArrayList<>();
        // create threads for writing to the queue
        for (int j = 0; j < writeThreadsNum; j++) {
            Thread writeThread = new Thread(() -> {
                while (source.size() > 0) {
                    synchronized (source) {
                        if (source.size() > 0) {
                            final FCInt elem = new FCInt(source.get(0));
                            fcq.add(elem);
                            mfcq.add(elem);
                            source.remove(0);
                        }
                    } // sync
                }
            });
            writeThreads.add(writeThread);
        }
        // start all write threads
        for (final Thread writeThread : writeThreads) {
            writeThread.start();
        }
        // wait for all write threads to finish
        for (final Thread writeThread : writeThreads) {
            try {
                writeThread.join();
            } catch (InterruptedException ex) {
                System.out.println("writeThread is interrupted");
            }
        }

        // verify hashes between FCQueue and Mock
        final byte[] fcqh = fcq.getHash().copyToByteArray();
        final byte[] mfcqh = mfcq.getHash().copyToByteArray();
        final byte[] fcqh_single = fcq_single.getHash().copyToByteArray();
        final byte[] mfcqh_single = mfcq_single.getHash().copyToByteArray();
        // compare FCQ to MFCQ, FCQ_single to MFCQ_single, cross versions
        for (int i = 0; i < fcqh.length; i++) {
            assertEquals(fcqh[i], fcqh_single[i], "single and multithreaded hashes are different");
            assertEquals(fcqh[i], mfcqh[i], "FCQ and Mock FCQ hashes are different");
            assertEquals(mfcqh[i], mfcqh_single[i], "single and multithreaded hashes are different");
            assertEquals(fcqh_single[i], mfcqh_single[i], "FCQ and Mock FCQ hashes are different"); // redundant
        }
    }

    /**
     * A multithreaded remove test: removing a bunch of values with threads
     * to test collisions.
     *
     * These MockFCQueue methods are tested:
     *
     * getHash(), .add(), .remove()
     */
    @ParameterizedTest
    @MethodSource("buildSmallArgs")
    @Tag(FCQUEUE)
    @DisplayName("Mock multithread Hash Test 2")
    void multithreadHashTest2(final int removeThreadsNum, final Supplier<FCQueue<FCInt>> supplier) {
        final int QUEUE_SIZE = 10_000;

        // make an array of source material to choose from
        final List<Integer> source = new ArrayList<>();
        for (int i = 0; i < QUEUE_SIZE; i++) {
            source.add(rnd.nextInt(NEXT_INT_BOUNDS));
        }

        // build multiple mutable copies of the queues -- every entry twice
        final FCQueue<FCInt> fcq_single = new FCQueue<>();
        final FCQueue<FCInt> mfcq_single = supplier.get();
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = supplier.get();
        for (int i = 0; i < QUEUE_SIZE; i++) {
            final FCInt elem0 = new FCInt(source.get(i));
            fcq_single.add(elem0);
            mfcq_single.add(elem0);
            fcq.add(elem0);
            mfcq.add(elem0);
        }

        for (int i = 0; i < QUEUE_SIZE; i++) {
            final FCInt elem0 = new FCInt(source.get(i));
            fcq_single.add(elem0);
            mfcq_single.add(elem0);
            fcq.add(elem0);
            mfcq.add(elem0);
        }

        // now delete half of the entries from the _single versions
        for (int i = 0; i < QUEUE_SIZE; i++) {
            fcq_single.remove();
            mfcq_single.remove();
        }

        // threads delete
        final List<Thread> removeThreads = new ArrayList<>();
        // create threads for writing to the queue
        for (int j = 0; j < removeThreadsNum; j++) {
            final Thread removeThread = new Thread(() -> {
                while (source.size() > 0) {
                    synchronized (source) {
                        if (source.size() > 0) {
                            fcq.remove();
                            mfcq.remove();
                            source.remove(0); // using the source as a synchronous counter
                        }
                    } // sync
                }
            });
            removeThreads.add(removeThread);
        }

        // start all remove threads
        for (final Thread removeThread : removeThreads) {
            removeThread.start();
        }
        // wait for all remove threads to finish
        for (final Thread removeThread : removeThreads) {
            try {
                removeThread.join();
            } catch (InterruptedException ex) {
                System.out.println("removeThread is interrupted");
            }
        }

        // verify hashes between FCQueue and Mock
        final byte[] fcqh = fcq.getHash().copyToByteArray();
        final byte[] mfcqh = mfcq.getHash().copyToByteArray();
        final byte[] fcqh_single = fcq_single.getHash().copyToByteArray();
        final byte[] mfcqh_single = mfcq_single.getHash().copyToByteArray();
        // compare FCQ to MFCQ, FCQ_single to MFCQ_single, cross versions
        for (int i = 0; i < fcqh.length; i++) {
            assertEquals(fcqh[i], fcqh_single[i], "single and multithreaded hashes are different");
            assertEquals(fcqh[i], mfcqh[i], "FCQ and Mock FCQ hashes are different");
            assertEquals(mfcqh[i], mfcqh_single[i], "single and multithreaded hashes are different");
            assertEquals(fcqh_single[i], mfcqh_single[i], "FCQ and Mock FCQ hashes are different"); // redundant
        }
    }

    /**
     * Create a MockFCQueue, add elements to it.
     * Do the identical operations on a LinkedList object, and verify that the results are exactly the same
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("Add Test")
    void addTest(final int targetSize, final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> fcq = supplier.get();
        final Queue<FCInt> queue = new LinkedList<>();
        addElements(fcq, queue, targetSize);
        assertEquals(targetSize, fcq.size(), "Queue is incorrect size");
        assertTrue(elementsEquals(fcq, queue), "Element mismatch between Mock FCQ and queue");
    }

    /**
     * Create a MockFCQueue instance and a Queue instance, add the same elements to them.
     * remove the same number of elements from them, and verify that the results are exactly the same
     */
    @ParameterizedTest
    @MethodSource("buildRemovalArgs")
    @Tag(FCQUEUE)
    @DisplayName("Remove Test")
    void removeTest(int size, double removeRatio, final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> fcq = supplier.get();
        final Queue<FCInt> queue = new LinkedList<>();
        addElements(fcq, queue, size);

        // MockFCQueue doesn't support remove an element from the middle of the queue
        final FCInt elem = new FCInt(0);
        assertThrows(
                UnsupportedOperationException.class,
                () -> fcq.remove(elem),
                "shouldn't be able to remove from the middle of a queue");

        final Set<FCInt> singletonSet = Collections.singleton(elem);
        // MockFCQueue doesn't support removeAll
        assertThrows(
                UnsupportedOperationException.class,
                () -> fcq.removeAll(singletonSet),
                "removeAll should not be supported");

        int removeNum = (int) (size * removeRatio);
        while (removeNum > 0) {
            Assertions.assertEquals(queue.remove(), fcq.remove(), "element mismatch between Mock FCQ and Queue");
            removeNum--;
        }
        assertTrue(elementsEquals(fcq, queue), "element mismatch between Mock FCQ and Queue");
    }

    /**
     * Create a MockFCQueue, add elements to it.
     * Do the identical operations on a LinkedList object, and verify that the results are exactly the same
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("toArray Test")
    void toArrayTest(final int targetSize, final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> fcq = supplier.get();
        final Queue<FCInt> queue = new LinkedList<>();
        addElements(fcq, queue, targetSize);

        final Object[] fcqArray = fcq.toArray();
        final Object[] queueArray = queue.toArray();
        assertEquals(targetSize, fcqArray.length, "mismatch between object array and desired size");
        assertEquals(queueArray.length, fcqArray.length, "FCQ and Queue toArray() gives different sizes");
        for (int i = 0; i < targetSize; i++) {
            assertEquals(fcqArray[i], queueArray[i], "Array elements do not match");
        }
    }

    /**
     * add the same element to a MockFCQueue instance and a Queue instance
     * if queue is null, we only add elements into the MockFCQueue instance
     *
     * @param fcq
     * 		a MockFCQueue instance
     * @param queue
     * 		a Queue instance
     * @param targetSize
     * 		target size of the two instances
     */
    void addElements(final FCQueue<FCInt> fcq, final Queue<FCInt> queue, final int targetSize) {
        for (int i = 0; i < targetSize; i++) {
            FCInt fcInt = new FCInt(rnd.nextInt());
            fcq.add(fcInt);
            if (queue != null) {
                queue.add(fcInt);
            }
        }
    }

    /**
     * @return whether the elements in a MockFCQueue instance and a Queue instance are the same
     */
    static boolean elementsEquals(final FCQueue<FCInt> fcq, final Queue<FCInt> queue) {
        if (fcq.size() != queue.size()) {
            System.out.println("fcq and queue have different size");
            return false;
        }
        Iterator<FCInt> fcqIterator = fcq.iterator();
        Iterator<FCInt> queueIterator = queue.iterator();
        while (fcqIterator.hasNext() && queueIterator.hasNext()) {
            FCInt fcqEle = fcqIterator.next();
            FCInt queueEle = queueIterator.next();
            if (!fcqEle.equals(queueEle)) {
                System.out.println("fcq and queue have different element");
                return false;
            }
        }
        if (fcqIterator.hasNext() || queueIterator.hasNext()) {
            System.out.println(
                    "fcq and queue have different size, either MockFCQueue#size method or FCQueueIterator doesn't "
                            + "work"
                            + " "
                            + "properly");
            return false;
        }
        return true;
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
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("Serialize And Recover Test")
    void serializeAndRecoverTest(final int numElements, final Supplier<FCQueue<FCInt>> supplier) throws IOException {
        /* original MockFCQueue objects (not copies) */
        final FCQueue<FCInt> origFCQ = supplier.get();

        /* copy of the original MockFCQueue recovered through serialization */
        final FCQueue<FCInt> recoveredFCQ;

        // Build up the original MockFCQueue
        for (int i = 0; i < numElements; i++) {
            origFCQ.add(new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)));
        }

        // Serialize the original MockFCQueue
        final byte[] serializedQueue;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (final SerializableDataOutputStream dos = new SerializableDataOutputStream(bos)) {

                dos.writeSerializable(origFCQ, true);

                dos.flush();

                serializedQueue = bos.toByteArray();
            }
        }

        assertNotNull(serializedQueue, "serializedQueue is null");
        assertTrue(serializedQueue.length > 0, "serializedQueue is empty");

        // Recover the serialized MockFCQueue into the recoveredFCQ variable
        try (final ByteArrayInputStream bis = new ByteArrayInputStream(serializedQueue)) {
            try (final SerializableDataInputStream dis = new SerializableDataInputStream(bis)) {
                recoveredFCQ = dis.readSerializable();
            }
        }

        assertNotNull(recoveredFCQ, "deserialized queue is null");
        assertEquals(origFCQ.size(), recoveredFCQ.size(), "Deserialized FCQ is the wrong size");

        // Assert that the queues are identical in content and order
        assertArrayEquals(qToInts(origFCQ), qToInts(recoveredFCQ), "Array contents do not match");

        // Assert that the queues are equal based on Object::equals
        assertEquals(origFCQ, recoveredFCQ, "FCQ and deserialized FCQ elements do not match");

        // Assert that both have the same Object::hashCode
        assertEquals(origFCQ.hashCode(), recoveredFCQ.hashCode(), "deserialized FCQ Hash does not match");
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
        final FCInt[] elements2 = queue.toArray(new FCInt[0]); // right size
        final FCInt[] elements3 = queue.toArray(new FCInt[0]); // too small
        final FCInt[] elements4 = new FCInt[queue.size() + 5]; // too large

        elements4[queue.size()] = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS)); // this should be set to null by toArray
        // (array)
        queue.toArray(elements4);

        int[] values = new int[queue.size()];
        int i = 0;
        for (FCInt e : queue) {
            values[i] = e.getValue();
            i++;
        }

        // the iterator should return exactly size() elements
        assertEquals(queue.size(), i, String.format("The iterator should return exactly %d elements", queue.size()));

        // check that toArray() and toArray(array) give the same answer as iterating through it
        assertEquals(elements1.length, values.length, "queue holds wrong number of values");
        for (int j = 0; j < elements1.length; j++) {
            Assertions.assertEquals(((FCInt) elements1[j]).getValue(), values[j], "toArray produced wrong value");
            Assertions.assertEquals((elements1[j]), elements2[j], "element mismatch");
            Assertions.assertEquals((elements1[j]), elements3[j], "element mismatch");
            Assertions.assertEquals((elements1[j]), elements4[j], "element mismatch");
            elements1[j] = null;
        }

        Assertions.assertNull(
                elements4[queue.size()],
                "element should be null"); // check that toArray(t[]) puts a null after the data
        return values;
    }

    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Serialization and deserialization of MockFCQueue with zero elements")
    void validateSerializeDeserializeWithZeroElements(final Supplier<FCQueue<FCInt>> supplier) {
        try {
            final FCQueue<FCInt> fcq = supplier.get();
            assertEquals(0, fcq.size(), "Mock FCQ is not empty");

            final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            final SerializableDataOutputStream outputStream = new SerializableDataOutputStream(outStream);

            outputStream.writeSerializableIterableWithSize(Collections.emptyIterator(), 0, true, false);

            final ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
            final SerializableDataInputStream inputStream = new SerializableDataInputStream(inStream);
            inputStream.readSerializableIterableWithSize(10, fcq::add);
        } catch (Exception ex) {
            // should not fail with EOFException
            assertFalse(ex instanceof java.io.EOFException, "unexpected exception type");
            ex.printStackTrace();
        }
    }

    @ParameterizedTest
    @MethodSource("buildOnlySuppliers")
    @Tag(FCQUEUE)
    @DisplayName("Adding empty list doesn't change the internals of MockFCQueue")
    void noChangesWhenAddingEmpty(final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> mockFCQueue = supplier.get();
        mockFCQueue.add(new FCInt(100));

        final Iterator<FCInt> iterator = mockFCQueue.iterator();
        mockFCQueue.addAll(Collections.emptyList());
        if (iterator.hasNext()) {
            Assertions.assertEquals(100, iterator.next().getValue(), "Incorrect value for iterator position");
        } else {
            fail("Iterator should have one element");
        }
    }

    /**
     * Creates a given number of threads which write on the same MockFCQueue
     * Verifies that none of the writes is lost
     *
     * @param writeThreadsNum
     * 		the number of threads write on the original MockFCQueue
     */
    @ParameterizedTest
    @MethodSource("buildSmallArgs")
    @Tag(FCQUEUE)
    @DisplayName("Multiple threads write on a MockFCQueue")
    void multipleWriteThreadsTest(final int writeThreadsNum, final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> original = supplier.get();
        final int targetSize = 10000;
        final int targetSizePerThread = targetSize / writeThreadsNum;

        final List<Thread> writeThreads = new ArrayList<>();
        // create threads for writing on the original MockFCQueue
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
            } catch (InterruptedException ex) {
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
            assertEquals(writeThreadsNum, count, "wrong thread number");
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    void testCopyAndMutability(final int size, final Supplier<FCQueue<FCInt>> mockSupplier) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = mockSupplier.get();
        for (int index = 0; index < size; index++) {
            final FCInt element = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
            fcq.add(element);
            mfcq.add(element);
        }

        assertEquals(mfcq.getHash(), fcq.getHash(), "Hashes must match");

        final FCQueue<FCInt> fcq01 = fcq.copy();
        final FCQueue<FCInt> mfcq01 = mfcq.copy();

        for (int index = 0; index < size; index++) {
            final FCInt element = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
            fcq01.add(element);
            mfcq01.add(element);
        }

        final FCQueue<FCInt> fcq02 = fcq01.copy();
        final FCQueue<FCInt> mfcq02 = mfcq01.copy();

        for (int index = 0; index < size; index++) {
            final FCInt element = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
            fcq02.add(element);
            mfcq02.add(element);
        }

        assertEquals(mfcq01.getHash(), fcq01.getHash(), "Hashes must match");
        assertEquals(mfcq02.getHash(), fcq02.getHash(), "Hashes must match");
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(FCQUEUE)
    @DisplayName("Validates removal of hash tail from queue")
    void hashTestRemovalHashTail(int queueSize, final Supplier<FCQueue<FCInt>> supplier) {
        final FCQueue<FCInt> fcq = new FCQueue<>();
        final FCQueue<FCInt> mfcq = supplier.get();

        if (queueSize == 1) {
            queueSize = 2;
        }

        final int limit = queueSize / 2;
        for (int i = 0; i < limit; i++) {
            final FCInt elem = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
            fcq.add(elem);
            mfcq.add(elem);
        }

        assertEquals(mfcq.getHash(), fcq.getHash(), "FCQ and Mock FCQ hashes are different after adding");

        for (int i = 0; i < limit; i++) {
            final FCInt elem = new FCInt(rnd.nextInt(NEXT_INT_BOUNDS));
            fcq.add(elem);
            mfcq.add(elem);
        }

        for (int i = 0; i < limit; i++) {
            fcq.remove();
            mfcq.remove();
        }

        assertEquals(mfcq.getHash(), fcq.getHash(), "FCQ and Mock FCQ hashes are different after adding");

        // now remove one element at a time and assure that the hashes remain the same
        int count = 0;
        while (!mfcq.isEmpty()) {
            fcq.remove();
            mfcq.remove();

            // hashes should stay identical
            assertEquals(
                    mfcq.getHash(),
                    fcq.getHash(),
                    String.format("FCQ and Mock FCQ hashes are different after removal %d", count++));
        }

        assertEquals(mfcq.getHash(), fcq.getHash(), String.format("FCQ and Mock FCQ hashes are different"));
    }

    @Test
    void nullHashTest() {
        final MockFCQueue<FCInt> queue = new MockFCQueue<>();
        final int numberOfElements = 2;
        for (int index = 0; index < numberOfElements; index++) {
            queue.add(new FCInt(index));
        }

        final MockFCQueue<FCInt> mutableQueue = queue.copy();
        for (int index = 0; index < numberOfElements; index++) {
            mutableQueue.remove();
        }

        for (byte h : mutableQueue.getHash().copyToByteArray()) {
            assertEquals(0, h);
        }
    }

    @Test
    void removeHashAfterCopy() {
        final FCQueue<FCInt> queue = new FCQueue<>();
        final MockFCQueue<FCInt> mockFCQueue = new MockFCQueue<>();

        for (int index = 0; index < 2; index++) {
            final FCInt element = new FCInt(index);
            queue.add(element);
            mockFCQueue.add(element);
        }

        final FCQueue<FCInt> mutableQueue = queue.copy();
        final MockFCQueue<FCInt> mutableMockQueue = mockFCQueue.copy();

        final Hash copyQueueHash = queue.getHash();
        final Hash copyMockQueueHash = mockFCQueue.getHash();

        assertEquals(copyMockQueueHash, copyQueueHash);

        mutableQueue.remove();
        mutableMockQueue.remove();

        final Hash mutableQueueHash = mutableQueue.getHash();
        final Hash mutableMockQueueHash = mutableMockQueue.getHash();

        assertEquals(mutableMockQueueHash, mutableQueueHash);
    }

    protected Stream<Arguments> buildArguments() {
        final int[] sizes = new int[] {1, 10, 100, 1_000, 10_000};
        final List<Arguments> arguments = new ArrayList<>();
        for (int size : sizes) {
            arguments.add(Arguments.of(size, MockFCQueue.mockSupplier));
            arguments.add(Arguments.of(size, SlowMockFCQueue.slowSupplier));
        }

        return arguments.stream();
    }

    protected Stream<Arguments> buildSmallArgs() {
        final int[] sizes = new int[] {1, 5, 20, 100, 200};
        final List<Arguments> arguments = new ArrayList<>();
        for (int size : sizes) {
            arguments.add(Arguments.of(size, MockFCQueue.mockSupplier));
            arguments.add(Arguments.of(size, SlowMockFCQueue.slowSupplier));
        }

        return arguments.stream();
    }

    protected Stream<Arguments> buildRemovalArgs() {
        final List<Arguments> arguments = new ArrayList<>();
        final int[] sizes = new int[] {1_000, 10_000};
        final double[] ratios = new double[] {0.2, 0.5, 0.8, 1.0};
        for (int size : sizes) {
            for (double ratio : ratios) {
                arguments.add(Arguments.of(size, ratio, MockFCQueue.mockSupplier));
                arguments.add(Arguments.of(size, ratio, SlowMockFCQueue.slowSupplier));
            }
        }
        return arguments.stream();
    }

    protected Stream<Arguments> buildOnlySuppliers() {
        final List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(MockFCQueue.mockSupplier));
        arguments.add(Arguments.of(SlowMockFCQueue.slowSupplier));
        return arguments.stream();
    }
}
