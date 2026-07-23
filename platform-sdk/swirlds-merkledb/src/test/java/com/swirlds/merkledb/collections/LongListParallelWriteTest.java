// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.collections.AbstractLongList.FILE_HEADER_SIZE_V3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.utility.test.fixtures.file.AbstractFileManagerAwareTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LongListParallelWriteTest extends AbstractFileManagerAwareTest {

    private static final int LONGS_PER_CHUNK = 33;
    private static final int CHUNK_COUNT = 40;
    private static final int CAPACITY = LONGS_PER_CHUNK * CHUNK_COUNT * 2;
    private static final int MIN_VALID_INDEX = LONGS_PER_CHUNK * 2 + 11;
    private static final int SIZE = LONGS_PER_CHUNK * CHUNK_COUNT - 7;
    private static final int ABSENT_CHUNK_INDEX = 3;
    private static final int THREADS_PER_LIST = 16;
    private static final long TIMEOUT_SECONDS = 10;

    @ParameterizedTest
    @EnumSource(Implementation.class)
    void parallelWriteMatchesSequentialWrite(final Implementation implementation, @TempDir final Path tempDir)
            throws Exception {
        try (final LongList source = createList(implementation)) {
            populateList(source);

            final Path sequentialFile = tempDir.resolve("sequential.ll");
            final Path parallelFile = tempDir.resolve("parallel.ll");
            source.writeToFile(sequentialFile);

            final AtomicInteger executedTasks = new AtomicInteger();
            try (final ExecutorService executor = Executors.newFixedThreadPool(THREADS_PER_LIST)) {
                source.writeToFile(
                        parallelFile,
                        task -> executor.execute(() -> {
                            executedTasks.incrementAndGet();
                            task.run();
                        }),
                        THREADS_PER_LIST);
            }

            assertEquals(THREADS_PER_LIST, executedTasks.get());
            assertEquals(-1L, Files.mismatch(sequentialFile, parallelFile));
            assertEquals(FILE_HEADER_SIZE_V3 + (long) (SIZE - MIN_VALID_INDEX) * Long.BYTES, Files.size(parallelFile));

            try (final LongList restored = createList(implementation, parallelFile)) {
                assertEquals(MIN_VALID_INDEX, restored.getMinValidIndex());
                assertEquals(SIZE - 1, restored.getMaxValidIndex());
                assertEquals(SIZE, restored.size());
                for (int index = MIN_VALID_INDEX; index < SIZE; index++) {
                    final int chunkIndex = index / LONGS_PER_CHUNK;
                    final long expected = chunkIndex == ABSENT_CHUNK_INDEX ? 0 : index + 1L;
                    assertEquals(expected, restored.get(index));
                }
            }
        }
    }

    @Test
    void oneThreadUsesSequentialWriter(@TempDir final Path tempDir) throws Exception {
        try (final LongList source = createList(Implementation.HEAP)) {
            populateList(source);

            final Path sequentialFile = tempDir.resolve("sequential.ll");
            final Path oneThreadFile = tempDir.resolve("one-thread.ll");
            final AtomicInteger executionCount = new AtomicInteger();
            final Executor executor = command -> {
                executionCount.incrementAndGet();
                command.run();
            };

            source.writeToFile(sequentialFile);
            source.writeToFile(oneThreadFile, executor, 1);

            assertEquals(0, executionCount.get());
            assertEquals(-1L, Files.mismatch(sequentialFile, oneThreadFile));
        }
    }

    @Test
    void parallelWriteUsesOnlyConfiguredExecutorTasks(@TempDir final Path tempDir) throws Exception {
        final int threadsPerList = 2;
        final Thread callerThread = Thread.currentThread();
        final AtomicInteger submittedTasks = new AtomicInteger();
        final AtomicInteger writtenRanges = new AtomicInteger();
        final AtomicInteger callerRanges = new AtomicInteger();

        try (final ControlledLongList source = new ControlledLongList(4, (ignoredStart, ignoredEnd) -> {
                    writtenRanges.incrementAndGet();
                    if (Thread.currentThread() == callerThread) {
                        callerRanges.incrementAndGet();
                    }
                });
                final ExecutorService executorService = Executors.newFixedThreadPool(threadsPerList)) {
            final Executor executor = command -> {
                submittedTasks.incrementAndGet();
                executorService.execute(command);
            };

            source.writeToFile(tempDir.resolve("parallel-write.ll"), executor, threadsPerList);

            assertEquals(threadsPerList, submittedTasks.get());
            assertEquals(threadsPerList, writtenRanges.get());
            assertEquals(0, callerRanges.get());
        }
    }

    @Test
    void parallelFailurePropagatesAfterAllTasksComplete(@TempDir final Path tempDir) throws Exception {
        final IOException expectedFailure = new IOException("expected failure");
        final CountDownLatch blockingTaskEntered = new CountDownLatch(1);
        final CountDownLatch releaseBlockingTask = new CountDownLatch(1);
        final CountDownLatch blockingTaskExited = new CountDownLatch(1);
        final CountDownLatch failureThrown = new CountDownLatch(1);

        try (final ControlledLongList source = new ControlledLongList(2, (startIndex, ignored) -> {
                    if (startIndex == 0) {
                        blockingTaskEntered.countDown();
                        try {
                            await(releaseBlockingTask);
                        } finally {
                            blockingTaskExited.countDown();
                        }
                    } else if (startIndex == 1) {
                        await(blockingTaskEntered);
                        failureThrown.countDown();
                        throw expectedFailure;
                    }
                });
                final ExecutorService executor = Executors.newFixedThreadPool(2);
                final ExecutorService writerExecutor = Executors.newSingleThreadExecutor()) {
            final Future<?> write = writerExecutor.submit(() -> {
                source.writeToFile(tempDir.resolve("failed-parallel-write.ll"), executor, 2);
                return null;
            });
            try {
                assertTrue(failureThrown.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertFalse(write.isDone());
            } finally {
                releaseBlockingTask.countDown();
            }

            final ExecutionException exception =
                    assertThrows(ExecutionException.class, () -> write.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertSame(expectedFailure, exception.getCause());
            assertEquals(0, blockingTaskExited.getCount());
        }
    }

    private void populateList(final LongList source) {
        source.updateValidRange(MIN_VALID_INDEX, SIZE - 1);
        for (int index = MIN_VALID_INDEX; index < SIZE; index++) {
            final int chunkIndex = index / LONGS_PER_CHUNK;
            if (chunkIndex != ABSENT_CHUNK_INDEX) {
                source.put(index, index + 1L);
            }
        }
    }

    private LongList createList(final Implementation implementation) {
        return switch (implementation) {
            case HEAP -> new LongListHeap(LONGS_PER_CHUNK, CAPACITY, 0);
            case OFF_HEAP -> new LongListOffHeap(LONGS_PER_CHUNK, CAPACITY, 0);
            case SEGMENT -> new LongListSegment(LONGS_PER_CHUNK, CAPACITY, 0);
            case DISK -> new LongListDisk(LONGS_PER_CHUNK, CAPACITY, 0, fileSystemManager);
            case DISK_SEGMENT -> new LongListDiskSegment(LONGS_PER_CHUNK, CAPACITY, 0, fileSystemManager);
        };
    }

    private LongList createList(final Implementation implementation, final Path file) throws IOException {
        return switch (implementation) {
            case HEAP -> new LongListHeap(file, LONGS_PER_CHUNK, CAPACITY, 0);
            case OFF_HEAP -> new LongListOffHeap(file, LONGS_PER_CHUNK, CAPACITY, 0);
            case SEGMENT -> new LongListSegment(file, LONGS_PER_CHUNK, CAPACITY, 0);
            case DISK -> new LongListDisk(file, LONGS_PER_CHUNK, CAPACITY, 0, fileSystemManager);
            case DISK_SEGMENT -> new LongListDiskSegment(file, LONGS_PER_CHUNK, CAPACITY, 0, fileSystemManager);
        };
    }

    private static void await(final CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for test coordination");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for test coordination", e);
        }
    }

    private enum Implementation {
        HEAP,
        OFF_HEAP,
        SEGMENT,
        DISK,
        DISK_SEGMENT
    }

    @FunctionalInterface
    private interface RangeWriter {
        void write(int startIndex, int endIndex) throws IOException;
    }

    private static final class ControlledLongList extends AbstractLongList<Object> {

        private final RangeWriter rangeWriter;

        private ControlledLongList(final int chunkCount, final RangeWriter rangeWriter) {
            super(1, chunkCount, 0);
            this.rangeWriter = rangeWriter;
            minValidIndex.set(0);
            maxValidIndex.set(chunkCount - 1L);
            size.set(chunkCount);
        }

        @Override
        protected Object readChunkData(
                final FileChannel fileChannel, final int chunkIndex, final int startIndex, final int endIndex) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void putToChunk(final Object chunk, final int subIndex, final long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected boolean putIfEqual(final Object chunk, final int subIndex, final long oldValue, final long newValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void writeLongsData(
                final FileChannel fc, final long startIndex, final long endIndex, final long fileOffset)
                throws IOException {
            rangeWriter.write(Math.toIntExact(startIndex), Math.toIntExact(endIndex));
        }

        @Override
        protected long lookupInChunk(final Object chunk, final long subIndex) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void partialChunkCleanup(final Object chunk, final boolean leftSide, final long entriesToCleanUp) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Object createChunk() {
            throw new UnsupportedOperationException();
        }
    }
}
