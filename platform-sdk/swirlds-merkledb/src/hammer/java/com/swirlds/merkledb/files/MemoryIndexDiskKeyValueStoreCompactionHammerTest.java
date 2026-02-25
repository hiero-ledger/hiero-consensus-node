// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.utility.StopWatch;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Hammers the {@link MemoryIndexDiskKeyValueStore} with a ton of concurrent changes to validate the
 * index and the data files are in sync and have all the data they should have.
 */
@Disabled("This test needs to be investigated")
class MemoryIndexDiskKeyValueStoreCompactionHammerTest {

    /** Temporary directory provided by JUnit */
    @TempDir
    Path testDirectory;

    @BeforeAll
    public static void setup() {
        Configurator.setRootLevel(Level.WARN);
    }

    @AfterAll
    public static void cleanUp() {
        Configurator.reconfigure();
    }

    /**
     * Hammers the {@link MemoryIndexDiskKeyValueStore} looking for any race conditions or weakness
     * in the implementation that can be observed due to load. The configuration options here are
     * intended to create new files rapidly. This is not a stress test on the size of the files,
     * only the frequency in which they occur. It was designed to find the bug #4514.
     *
     * @param chanceOfAddElement Number of times out of 100 that we will add something
     * @param chanceOfDeleteElement Number of times out of 100 that we will delete something
     * @param numIterationsPerFlush Number of changes per flush
     * @param testDurationInSeconds Number of seconds to run this test
     * @throws IOException Due to database exceptions
     * @throws InterruptedException Due to sleeps and waits
     */
    @ParameterizedTest
    @CsvSource({
        "20,5,100,300", // Shorter test. If something goes wrong, this usually catches it.
        "20,5,100,600"
    }) // Longer test. For 10 minutes we'll hammer on this thing.
    void testMerge(
            final int chanceOfAddElement,
            final int chanceOfDeleteElement,
            final int numIterationsPerFlush,
            final int testDurationInSeconds)
            throws IOException, InterruptedException {

        // Collection of database files and index
        LongListOffHeap storeIndex = new LongListOffHeap(1024 * 1024, 2L * 1024 * 1024, 256 * 1024);
        final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final var store = new MemoryIndexDiskKeyValueStore(
                dbConfig,
                testDirectory.resolve("megaMergeHammerTest"),
                "megaMergeHammerTest",
                null,
                (dataLocation, dataValue) -> {},
                storeIndex);

        // This is just a nice little output that you can copy and paste to watch the database
        // directory do its thing
        System.out.println("watch ls -Rlh " + testDirectory.toAbsolutePath());

        // We're going to have a few threads all working at the same time. One thread produces new
        // data and
        // flushes it to disk. Every time it flushes to disk, it then reads back ALL the data from
        // disk and
        // validates that everything it expected to be there, was there, and in the right order, and
        // nothing extra
        // was included.
        //
        // The second thread compacts files. Before it merges the files it walks over each
        // file and creates
        // a record of the most recent versions of each data item. Then after the merge it walks
        // over the resulting
        // files and makes sure that everything expected to be there, was there, and in the right
        // order, and nothing
        // extra was included.
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        // Start a thread that will write a bunch of stuff to disk, really quickly. If this thread
        // fails validation
        // at any time, or fails for some other reason, the failure reason will be extractable from
        // the future.
        final var modifier =
                new FakeVirtualMap(store, chanceOfAddElement, chanceOfDeleteElement, numIterationsPerFlush);
        final Future<Void> modifierFuture = executor.submit(modifier);

        // Start a thread for merging files together. The future will throw an exception if one
        // occurs on the thread.
        final Compactor compactor = new Compactor(dbConfig, store, storeIndex);
        final Future<Void> mergeFuture = executor.submit(compactor);

        // We need to terminate the test if an error occurs in fail-fast manner. So we will keep a
        // record of
        // when the test should terminate, looping until it does, with a little sleep to avoid
        // busy-looping
        // the CPU. Each time we enter the loop, we check whether either of the futures is done, and
        // whether
        // they have thrown an exception. That way, if either thread throws, we can terminate
        // quickly.
        final var endTime = System.currentTimeMillis() + (testDurationInSeconds * 1000L);
        while (endTime > System.currentTimeMillis()) {
            if (modifierFuture.isDone()) {
                assertDoesNotThrow(
                        (ThrowingSupplier<Void>) modifierFuture::get,
                        "Should not throw, something failed while modifying.");
            }

            if (mergeFuture.isDone()) {
                assertDoesNotThrow(
                        (ThrowingSupplier<Void>) mergeFuture::get, "Should not throw, something failed while merging.");
            }

            MILLISECONDS.sleep(10);
        }

        // OK, the test has completed, so stop both background threads (gracefully).
        modifier.stop();
        compactor.stop();

        // Check both futures with a blocking call to see if they throw errors. If not, we're good
        // (no ISS).
        assertDoesNotThrow(
                (ThrowingSupplier<Void>) modifierFuture::get, "Should not throw, something failed while modifying.");
        assertDoesNotThrow(
                (ThrowingSupplier<Void>) mergeFuture::get, "Should not throw, something failed while merging.");
        store.close();
    }

    /**
     * Helper class extended by both The {@link FakeVirtualMap} and {@link Compactor}. Can be stopped.
     */
    private abstract static class Worker implements Callable<Void> {
        /** For convenience of subclasses */
        protected final Random rand = new Random(12_9_2021);
        /** Used to flag whether it is time to stop this thread */
        private final AtomicBoolean stop = new AtomicBoolean(false);

        /**
         * Asynchronously stops the thread gracefully. Any current work on the thread will complete
         * before the worker terminates.
         */
        public void stop() {
            stop.set(true);
        }

        /** {@inheritDoc} */
        @Override
        public final Void call() {
            try {
                while (!stop.get()) {
                    doWork();
                }
                return null;
            } catch (final Throwable th) {
                throw new RuntimeException(th);
            }
        }

        /**
         * Overridden in subclasses to perform the actual work.
         *
         * @throws Exception In case something goes wrong.
         */
        protected abstract void doWork() throws Exception;
    }

    /**
     * Generates data files in a manner similar to a {@link com.swirlds.virtualmap.VirtualMap}.
     * Simulates tree modifications such as adds, updates, and deletes. Although a real map could be
     * used (and maybe should be), this implementation is lower-level and permits for more direct
     * modification of the {@link MemoryIndexDiskKeyValueStore} making it somewhat easier to
     * directly inspect what is happening if there is an error.
     */
    private static final class FakeVirtualMap extends Worker {
        /**
         * A tombstone value to represent that the entry in the cache represents a deleted element
         */
        private static final long DELETED = Long.MIN_VALUE;
        /** The first leaf path. We keep track of this and make sure it is valid. */
        private long firstPath = -1;
        /** The last leaf path. We keep track of this and make sure it is valid. */
        private long lastPath = -1;
        /**
         * A map of all key/value pairs that we expect to be in the database as of the last flush.
         */
        private final Map<Long, Long> expected = new HashMap<>(); // path->value
        /** The chance of performing an add */
        private final int chanceOfAddElement;
        /** The chance of performing a delete operation */
        private final int chanceOfDeleteElement;
        /** The chance of performing an update */
        private final int numIterationsPerFlush;
        /** Represents the database */
        private final MemoryIndexDiskKeyValueStore coll;

        private final AtomicInteger counter = new AtomicInteger(0);

        public FakeVirtualMap(
                final MemoryIndexDiskKeyValueStore coll,
                final int chanceOfAddElement,
                final int chanceOfDeleteElement,
                final int numIterationsPerFlush) {
            this.coll = coll;
            this.chanceOfAddElement = chanceOfAddElement;
            this.chanceOfDeleteElement = chanceOfDeleteElement;
            this.numIterationsPerFlush = numIterationsPerFlush;
        }

        /**
         * Iterates some number of times, collecting some pseudo-random number of delete, add, and
         * update operations in a cache. Then, after all the mutations are collected, flushes the
         * cache to disk. It also keeps track of the expected state, and validates that
         * <strong>ALL</strong> state that we expect in the database, is visible in the database.
         *
         * @throws Exception If it fails.
         */
        @Override
        protected void doWork() throws Exception {
            final Map<Long, Long> cache = new HashMap<>();
            for (int i = 0; i < numIterationsPerFlush; i++) {
                final int chance = rand.nextInt(100);
                final int numElements = getNumElements();
                if (chance < chanceOfDeleteElement && numElements != 0) {
                    doDelete(cache);
                } else if (chance < chanceOfAddElement) {
                    doAdd(cache);
                } else if (numElements > 0) {
                    doUpdate(cache);
                }
            }

            save(cache);
            validate();
            MILLISECONDS.sleep(10);
            counter.getAndIncrement();
        }

        /**
         * Adds an element. Simulates (in a way) what happens in the real virtual map by shifting
         * the element at the first path to the end, and then adds the new element after that.
         * Adjusts the {@link #firstPath} and {@link #lastPath} appropriately.
         *
         * @param cache The cache to populate with the added element
         */
        private void doAdd(final Map<Long, Long> cache) {
            final int numElements = getNumElements();
            if (numElements == 0) {
                // This is the first element, so we can just put it.
                cache.put(0L, 0L);
                firstPath = 0L;
                lastPath = 0L;
            } else {
                // Move the first element to the end of the list.
                final long oldFirstPath = firstPath++;
                final long newPathForOldElement = ++lastPath;
                final long oldValue = find(cache, oldFirstPath);
                cache.put(oldFirstPath, DELETED);
                cache.put(newPathForOldElement, oldValue);
                // Insert the last element
                final long newPathForNewElement = ++lastPath;
                final long newValue = newRandomValue();
                cache.put(newPathForNewElement, newValue);
            }
        }

        /**
         * Creates an update mutation and puts it in the cache. An existing leaf is chosen at random
         * and updated. If the existing leaf is an ADD leaf, it will simply get a new value. If it
         * was a DELETED leaf, then it will no longer be deleted, but become updated instead.
         *
         * @param cache The cache to store the update in.
         */
        private void doUpdate(final Map<Long, Long> cache) {
            // Update an existing element at random
            final long path = getRandomElement();
            final long value = newRandomValue();
            cache.put(path, value);
        }

        /**
         * Simulates a "delete" operation in the map. Moves the last element to the position of the
         * deleted one, and moves the now newly last element (i.e. what was last - 1) to the front
         * position (min leaf path - 1), and marks both as being {@link #DELETED}.
         *
         * @param cache The cache to store the changes in.
         */
        private void doDelete(final Map<Long, Long> cache) {
            final long path = getRandomElement();

            // If the path isn't the last element, then we will just copy over the data from
            // the last slot to the deleted path
            if (path != lastPath) {
                cache.put(path, find(cache, lastPath));
            }

            // Now we will delete the last slot and then move the second-to-last to the front
            cache.put(lastPath, DELETED);
            if (--lastPath < firstPath) {
                firstPath = 0;
            } else {
                final var toBeMovedPath = lastPath--;
                final var oldValue = find(cache, toBeMovedPath);
                cache.put(toBeMovedPath, DELETED);
                cache.put(--firstPath, oldValue);
            }
        }

        /**
         * Saves everything in the cache to disk, and to the {@link #expected} map.
         *
         * @param cache The cache to get the data from
         * @throws IOException in case of emergency
         */
        private void save(final Map<Long, Long> cache) throws IOException {
            coll.updateValidKeyRange(firstPath, lastPath);
            coll.startWriting();
            final List<Long> sortedKeys = cache.keySet().stream().sorted().toList();
            for (final long key : sortedKeys) {
                if (key < firstPath || key > lastPath || key == DELETED) {
                    expected.remove(key);
                } else {
                    final long value = cache.get(key);
                    expected.put(key, value);
                    coll.put(
                            key,
                            o -> {
                                o.writeLong(key);
                                o.writeLong(value);
                            },
                            2 * Long.BYTES);
                }
            }
            coll.endWriting();
        }

        /**
         * Checks that the expected map contains the same number of elements as we expect, and that
         * <strong>EVERYTHING</strong> in the expected map is also present on disk. This is the KEY
         * FUNDAMENTAL CHECK of the test! If something goes wrong with merging, this check will find
         * it.
         *
         */
        private void validate() {
            StopWatch stopWatch = null;
            if (counter.get() % 8 == 0) {
                stopWatch = new StopWatch();
                stopWatch.start();
            }

            assertEquals(expected.size(), getNumElements(), "Expected map is incongruent with first and last paths");

            expected.keySet().parallelStream().forEach(key -> {
                try {
                    assertNotNull(coll.get(key), () -> String.format("Missing key %s in DB that we expected!", key));
                    assertEquals(
                            expected.get(key),
                            coll.get(key).getLong(Long.BYTES),
                            () -> String.format("Not the value for key %s from DB that we expected!", key));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            if (counter.get() % 8 == 0) {
                stopWatch.stop();
                System.out.printf("Validate took %s ms%n", stopWatch.getTime(MILLISECONDS));
            }
        }

        /**
         * Finds the value at the given path. It may be in the cache, or it may be in the expected
         * map, but it must be in one of those two places. Does not check the disk (the {@link
         * #validate()} method will do that later). The value we are looking for
         * <strong>MUST</strong> be in the cache or expected map, or we will error out. This is an
         * important safety check in the test to make sure our map is always exactly what we think
         * it should be.
         *
         * @param cache The cache to check
         * @param path The path of the value we're looking for
         * @return the value
         */
        private long find(final Map<Long, Long> cache, final long path) {
            final Long value = cache.get(path);
            return value == null ? expected.get(path) : value;
        }

        /**
         * @return the number of elements currently in the cache + expected map, minus any
         *     duplicates or deleted items.
         */
        private int getNumElements() {
            return (int) (lastPath == -1 ? 0 : lastPath - firstPath + 1);
        }

        /**
         * @return a random element path that is valid.
         */
        private long getRandomElement() {
            return rand.nextInt(getNumElements()) + firstPath;
        }

        /**
         * Creates a random value for a leaf. It is <strong>possible but extremely unlikely</strong>
         * that it will return the {@link #DELETED} tombstone value. If it does, it would produce an
         * incorrect state because we would not have adjusted things correctly. So even though this
         * is next to impossible, we raise an exception in this case.
         *
         * @return A new long, guaranteed not to be {@link #DELETED}
         */
        private long newRandomValue() {
            final long value = rand.nextLong();
            if (value == DELETED) {
                throw new AssertionError("You should have bought a lotto ticket because today was your lucky day."
                        + " Somehow you generated the DELETED tombstone. Pick another seed for"
                        + " the random generator and you will probably never see this message"
                        + " again.");
            }
            return value;
        }
    }

    /** Merges files together. */
    private static final class Compactor extends Worker {
        private int iteration = 1;
        private final DataFileCompactor compactor;

        Compactor(final MerkleDbConfig dbConfig, final MemoryIndexDiskKeyValueStore coll, LongListOffHeap storeIndex) {
            compactor = new DataFileCompactor(
                    dbConfig, "megaMergeHammerTest", coll.getFileCollection(), storeIndex, null, null, null, null);
        }

        @Override
        protected void doWork() throws Exception {

            if (iteration % 5 == 0) {
                compactor.compact();
            }
            iteration++;
        }
    }
}
