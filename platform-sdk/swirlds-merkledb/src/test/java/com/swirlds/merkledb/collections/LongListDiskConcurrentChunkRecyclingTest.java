// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify {@link LongListDisk} correctly handles concurrent reads
 * during chunk recycling (shrink + expand cycles).
 *
 * <p>The core scenario being tested:
 * <ol>
 *   <li>A list is populated with known values across multiple chunks</li>
 *   <li>Reader threads continuously read from the list</li>
 *   <li>A mutator thread shrinks the left side (freeing chunks) and
 *       immediately writes new values on the right side (reusing freed chunks)</li>
 *   <li>Readers must never observe a "ghost" value from a recycled chunk — they
 *       should see either the correct value or the default (0)</li>
 * </ol>
 *
 * <p>Without the {@code StampedLock} fix, this test reliably fails under load
 * because a reader can grab a chunk file-offset that is freed and reused for
 * a different index range between the offset lookup and the file read.
 */
class LongListDiskConcurrentChunkRecyclingTest {

    /**
     * Small chunk size to maximize the number of chunk boundaries and therefore
     * the frequency of chunk allocation / recycling.
     */
    private static final int LONGS_PER_CHUNK = 4;

    /**
     * Total capacity — enough to create many chunks that can be cycled through.
     * With 4 longs/chunk this gives us 250 chunks worth of address space.
     */
    private static final long CAPACITY = LONGS_PER_CHUNK * 250L;

    /**
     * Zero reserved buffer so that {@code updateValidRange} frees chunks
     * immediately — no grace window.
     */
    private static final long RESERVED_BUFFER = 0;

    /**
     * Magic offset added to index to produce the stored value.  Readers use
     * this to verify that a non-default value belongs to the right index.
     */
    private static final long VALUE_MAGIC = 1_000_000L;

    /** Number of concurrent reader threads. */
    private static final int READER_THREADS = 4;

    private LongListDisk list;

    @AfterEach
    void cleanup() {
        if (list != null) {
            list.close();
            list.resetTransferBuffer();
            list = null;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Test 1: Correctness — basic read/write with no concurrency issues
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Basic get() returns correct values after put()")
    void basicReadWriteCorrectness() {
        list = new LongListDisk(LONGS_PER_CHUNK, CAPACITY, RESERVED_BUFFER, CONFIGURATION);
        final int count = LONGS_PER_CHUNK * 10; // 10 chunks
        list.updateValidRange(0, count - 1);

        for (int i = 0; i < count; i++) {
            list.put(i, i + VALUE_MAGIC);
        }
        for (int i = 0; i < count; i++) {
            assertEquals(i + VALUE_MAGIC, list.get(i, 0), "Value at index " + i + " should match what was written");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Test 2: Correctness — get() returns default after chunk is freed
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("get() returns default for indices whose chunks have been freed")
    void readAfterShrinkReturnsDefault() {
        list = new LongListDisk(LONGS_PER_CHUNK, CAPACITY, RESERVED_BUFFER, CONFIGURATION);
        final int count = LONGS_PER_CHUNK * 10;
        list.updateValidRange(0, count - 1);

        for (int i = 0; i < count; i++) {
            list.put(i, i + VALUE_MAGIC);
        }

        // Shrink away the first 3 chunks
        final int newMin = LONGS_PER_CHUNK * 3;
        list.updateValidRange(newMin, count - 1);

        // Freed region should return default
        for (int i = 0; i < newMin; i++) {
            assertEquals(0, list.get(i, 0), "Index " + i + " should return default after its chunk was freed");
        }
        // Surviving region should still have correct values
        for (int i = newMin; i < count; i++) {
            assertEquals(i + VALUE_MAGIC, list.get(i, 0), "Index " + i + " should still have its original value");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Test 3: Correctness — chunk recycling produces correct values
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Values written into recycled chunks are read back correctly")
    void recycledChunkWriteReadCorrectness() {
        list = new LongListDisk(LONGS_PER_CHUNK, CAPACITY, RESERVED_BUFFER, CONFIGURATION);
        final int initialCount = LONGS_PER_CHUNK * 6;
        list.updateValidRange(0, CAPACITY - 1);

        // Fill first 6 chunks
        for (int i = 0; i < initialCount; i++) {
            list.put(i, i + VALUE_MAGIC);
        }

        // Free the first 3 chunks by shrinking
        final int newMin = LONGS_PER_CHUNK * 3;
        list.updateValidRange(newMin, CAPACITY - 1);

        // Write into indices beyond the initial range — these should reuse freed chunk offsets
        final int extendedStart = initialCount;
        final int extendedEnd = initialCount + LONGS_PER_CHUNK * 3;
        for (int i = extendedStart; i < extendedEnd; i++) {
            list.put(i, i + VALUE_MAGIC);
        }

        // Verify that old surviving values are intact
        for (int i = newMin; i < initialCount; i++) {
            assertEquals(i + VALUE_MAGIC, list.get(i, 0), "Surviving index " + i + " should retain its value");
        }
        // Verify new values in recycled chunks
        for (int i = extendedStart; i < extendedEnd; i++) {
            assertEquals(
                    i + VALUE_MAGIC,
                    list.get(i, 0),
                    "Recycled-chunk index " + i + " should have the newly written value");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Test 4: Concurrency — the main race-condition test
    //
    // Repeated 5 times to increase the chance of hitting the window.
    // ────────────────────────────────────────────────────────────────────

    @RepeatedTest(5)
    @DisplayName("Concurrent readers never observe stale values from recycled chunks")
    void concurrentReadersNeverSeeGhostValues() throws Exception {
        list = new LongListDisk(LONGS_PER_CHUNK, CAPACITY, RESERVED_BUFFER, CONFIGURATION);

        // ── Initial population: fill the first INITIAL_CHUNKS chunks ──
        final int INITIAL_CHUNKS = 20;
        final int initialCount = LONGS_PER_CHUNK * INITIAL_CHUNKS;
        list.updateValidRange(0, CAPACITY - 1);
        for (int i = 0; i < initialCount; i++) {
            list.put(i, i + VALUE_MAGIC);
        }

        // ── Shared state ──
        final AtomicBoolean stop = new AtomicBoolean(false);
        // Tracks the current min valid index so readers know which values are valid
        final AtomicLong currentMinValid = new AtomicLong(0);
        // Tracks how far right we have written
        final AtomicLong currentMaxWritten = new AtomicLong(initialCount - 1);
        // Counts observed anomalies (should be 0 with the fix)
        final AtomicLong anomalyCount = new AtomicLong(0);
        // Captures the first anomaly message for diagnostics
        final AtomicReference<String> firstAnomaly = new AtomicReference<>();

        final ExecutorService pool = Executors.newFixedThreadPool(READER_THREADS + 1);
        final CyclicBarrier startBarrier = new CyclicBarrier(READER_THREADS + 1);
        final List<Future<?>> futures = new ArrayList<>();

        // ── Reader threads ──
        for (int r = 0; r < READER_THREADS; r++) {
            final int readerId = r;
            futures.add(pool.submit(() -> {
                try {
                    startBarrier.await(); // synchronize start
                } catch (Exception e) {
                    return;
                }
                long localReads = 0;
                while (!stop.get()) {
                    // Read from a random index in the full range [0, maxWritten]
                    final long maxW = currentMaxWritten.get();
                    if (maxW < 0) continue;

                    // Spread readers across the range; use a simple deterministic
                    // pattern to maximize the chance of hitting recycled regions
                    final long idx = (localReads * 7 + readerId * 13) % (maxW + 1);
                    localReads++;

                    final long value = list.get(idx, 0);

                    if (value == 0) {
                        // Default — the chunk was freed or never written.  This is
                        // an acceptable outcome when the index is below currentMinValid.
                        continue;
                    }

                    final long expected = idx + VALUE_MAGIC;
                    if (value != expected) {
                        // ── ANOMALY: we read a non-default, non-matching value ──
                        // This is the signature of the chunk-recycling race: we read
                        // a value that belongs to a DIFFERENT index that now occupies
                        // the same file offset.
                        final long count = anomalyCount.incrementAndGet();
                        if (count == 1) {
                            firstAnomaly.set(String.format(
                                    "Reader %d: index=%d, expected=%d, actual=%d, " + "currentMin=%d, maxWritten=%d",
                                    readerId, idx, expected, value, currentMinValid.get(), maxW));
                        }
                    }
                }
            }));
        }

        // ── Mutator thread: repeatedly shrink-left + expand-right ──
        futures.add(pool.submit(() -> {
            try {
                startBarrier.await();
            } catch (Exception e) {
                return;
            }
            long minValid = 0;
            long maxWritten = initialCount - 1;
            // Each iteration frees SHRINK_STEP indices from the left and writes
            // SHRINK_STEP new indices on the right, causing chunk recycling.
            final int SHRINK_STEP = LONGS_PER_CHUNK; // exactly one chunk per step
            final int ITERATIONS = 150;

            for (int iter = 0; iter < ITERATIONS && !stop.get(); iter++) {
                // Shrink the left side
                minValid += SHRINK_STEP;
                list.updateValidRange(minValid, CAPACITY - 1);
                currentMinValid.set(minValid);

                // Write new values on the right (will reuse freed chunk offsets)
                final long writeStart = maxWritten + 1;
                final long writeEnd = writeStart + SHRINK_STEP;
                for (long i = writeStart; i < writeEnd && i < CAPACITY; i++) {
                    list.put(i, i + VALUE_MAGIC);
                }
                maxWritten = Math.min(writeEnd - 1, CAPACITY - 1);
                currentMaxWritten.set(maxWritten);

                // Brief yield to give readers a chance to hit the recycled region
                Thread.yield();
            }
            stop.set(true);
        }));

        // ── Wait for completion ──
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Pool should terminate");

        // ── Assert ──
        assertEquals(
                0, anomalyCount.get(), "Readers observed stale/recycled values! First anomaly: " + firstAnomaly.get());
    }

    // ────────────────────────────────────────────────────────────────────
    // Test 5: Concurrency — readers + writers on the SAME indices
    //
    // Verifies that concurrent put() + get() on overlapping indices
    // returns either the old value, the new value, or default — never
    // garbage from a different index.
    // ────────────────────────────────────────────────────────────────────

    @RepeatedTest(5)
    @DisplayName("Concurrent put and get on overlapping indices never return garbage")
    void concurrentPutAndGetNeverReturnGarbage() throws Exception {
        list = new LongListDisk(LONGS_PER_CHUNK, CAPACITY, RESERVED_BUFFER, CONFIGURATION);
        final int INDEX_RANGE = LONGS_PER_CHUNK * 20;
        list.updateValidRange(0, CAPACITY - 1);

        // Seed with generation 0
        for (int i = 0; i < INDEX_RANGE; i++) {
            list.put(i, i + VALUE_MAGIC);
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicInteger currentGeneration = new AtomicInteger(0);
        final AtomicLong anomalyCount = new AtomicLong(0);
        final AtomicReference<String> firstAnomaly = new AtomicReference<>();

        final ExecutorService pool = Executors.newFixedThreadPool(READER_THREADS + 1);
        final CyclicBarrier barrier = new CyclicBarrier(READER_THREADS + 1);
        final List<Future<?>> futures = new ArrayList<>();

        // Readers
        for (int r = 0; r < READER_THREADS; r++) {
            final int readerId = r;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    return;
                }
                long reads = 0;
                while (!stop.get()) {
                    final int idx = (int) ((reads * 7 + readerId * 3) % INDEX_RANGE);
                    reads++;
                    final long value = list.get(idx, 0);
                    if (value == 0) continue; // default is acceptable during transitions

                    // The value must equal idx + VALUE_MAGIC + (some_gen * INDEX_RANGE).
                    // In other words:  (value - VALUE_MAGIC) % INDEX_RANGE == idx
                    final long decoded = value - VALUE_MAGIC;
                    if (decoded < 0 || decoded % INDEX_RANGE != idx) {
                        final long cnt = anomalyCount.incrementAndGet();
                        if (cnt == 1) {
                            firstAnomaly.set(String.format(
                                    "Reader %d: idx=%d, value=%d, decoded=%d, gen=%d",
                                    readerId, idx, value, decoded, currentGeneration.get()));
                        }
                    }
                }
            }));
        }

        // Writer: overwrites all indices with a new generation value each pass
        futures.add(pool.submit(() -> {
            try {
                barrier.await();
            } catch (Exception e) {
                return;
            }
            for (int gen = 1; gen <= 200 && !stop.get(); gen++) {
                currentGeneration.set(gen);
                final long genOffset = (long) gen * INDEX_RANGE;
                for (int i = 0; i < INDEX_RANGE; i++) {
                    list.put(i, i + VALUE_MAGIC + genOffset);
                }
            }
            stop.set(true);
        }));

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(0, anomalyCount.get(), "Readers observed corrupt values! First: " + firstAnomaly.get());
    }

    // ────────────────────────────────────────────────────────────────────
    // Test 6: Concurrency — heavy chunk recycling with many readers
    //
    // This is the most aggressive variant.  It uses a very small chunk
    // size (2 longs) so that almost every mutator iteration frees a
    // chunk on the left via updateValidRange → closeChunk, which pushes
    // the file offset into freeChunks.  The subsequent put() calls to
    // higher indices trigger createOrGetChunk(), which polls that same
    // offset from freeChunks and reuses it for a completely different
    // index range.  Readers running in parallel must never observe a
    // value that belongs to the old index range at the recycled offset.
    // ────────────────────────────────────────────────────────────────────

    @RepeatedTest(3)
    @DisplayName("Heavy chunk recycling stress test")
    void heavyChunkRecyclingStress() throws Exception {
        // Even smaller chunks for maximum recycling frequency
        final int stressLongsPerChunk = 2;
        final long stressCapacity = stressLongsPerChunk * 500L;
        list = new LongListDisk(stressLongsPerChunk, stressCapacity, 0, CONFIGURATION);

        final int initiallyPopulatedCount = stressLongsPerChunk * 50;
        list.updateValidRange(0, stressCapacity - 1);
        for (int i = 0; i < initiallyPopulatedCount; i++) {
            list.put(i, i + VALUE_MAGIC);
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong currentMinValid = new AtomicLong(0);
        final AtomicLong currentMaxWritten = new AtomicLong(initiallyPopulatedCount - 1);
        final AtomicLong anomalyCount = new AtomicLong(0);
        final AtomicReference<String> firstAnomaly = new AtomicReference<>();

        final int readerCount = 6;
        final ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        final CyclicBarrier barrier = new CyclicBarrier(readerCount + 1);
        final List<Future<?>> futures = new ArrayList<>();

        for (int r = 0; r < readerCount; r++) {
            final int readerId = r;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    return;
                }
                long readCount = 0;
                while (!stop.get()) {
                    final long maxWrittenSnapshot = currentMaxWritten.get();
                    if (maxWrittenSnapshot <= 0) continue;
                    final long index = (readCount * 11 + readerId * 17) % (maxWrittenSnapshot + 1);
                    readCount++;
                    final long value = list.get(index, 0);
                    if (value == 0) continue;
                    final long expectedValue = index + VALUE_MAGIC;
                    if (value != expectedValue) {
                        anomalyCount.incrementAndGet();
                        firstAnomaly.compareAndSet(
                                null,
                                String.format(
                                        "Reader %d: index=%d expected=%d actual=%d minValid=%d maxWritten=%d",
                                        readerId,
                                        index,
                                        expectedValue,
                                        value,
                                        currentMinValid.get(),
                                        maxWrittenSnapshot));
                    }
                }
            }));
        }

        // Mutator: shrinks the left boundary by one chunk per iteration,
        // then writes values at higher indices.  The put() calls trigger
        // createOrGetChunk() which reuses the file offsets freed by the
        // preceding updateValidRange → closeChunk call.
        futures.add(pool.submit(() -> {
            try {
                barrier.await();
            } catch (Exception e) {
                return;
            }
            long minValidIndex = 0;
            long maxWrittenIndex = initiallyPopulatedCount - 1;
            final int iterations = 400;
            for (int iter = 0;
                    iter < iterations && !stop.get() && maxWrittenIndex + stressLongsPerChunk < stressCapacity;
                    iter++) {

                // Free one chunk on the left
                minValidIndex += stressLongsPerChunk;
                list.updateValidRange(minValidIndex, stressCapacity - 1);
                currentMinValid.set(minValidIndex);

                // Write one chunk's worth of values on the right.
                // createOrGetChunk() will poll the just-freed file offset from freeChunks.
                final long writeStart = maxWrittenIndex + 1;
                final long writeEnd = Math.min(writeStart + stressLongsPerChunk, stressCapacity);
                for (long i = writeStart; i < writeEnd; i++) {
                    list.put(i, i + VALUE_MAGIC);
                }
                maxWrittenIndex = writeEnd - 1;
                currentMaxWritten.set(maxWrittenIndex);
                // No yield — keep pressure high
            }
            stop.set(true);
        }));

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, anomalyCount.get(), "Stress test found stale reads! First: " + firstAnomaly.get());
    }

    // ────────────────────────────────────────────────────────────────────
    // Test 7: Regression — putIfEqual still works correctly
    //
    // Ensures the StampedLock changes don't break CAS semantics.
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("putIfEqual is not broken by StampedLock changes")
    void putIfEqualStillWorksCorrectly() {
        list = new LongListDisk(LONGS_PER_CHUNK, CAPACITY, RESERVED_BUFFER, CONFIGURATION);
        list.updateValidRange(0, 99);

        list.put(42, 100);
        assertEquals(100, list.get(42, 0));

        // Wrong expected → no change
        boolean changed = list.putIfEqual(42, 999, 200);
        assertFalse(changed);
        assertEquals(100, list.get(42, 0));

        // Correct expected → swap
        changed = list.putIfEqual(42, 100, 200);
        assertTrue(changed);
        assertEquals(200, list.get(42, 0));
    }
}
