// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import com.swirlds.config.api.Configuration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

class LongListDiskSegmentTest extends AbstractLongListTest<LongListDiskSegment> {

    @Override
    protected LongListDiskSegment createLongList(long capacity, Configuration config) {
        return new LongListDiskSegment(capacity, config, fileSystemManager);
    }

    @Override
    protected LongListDiskSegment createLongList(
            final int longsPerChunk, final long capacity, final long reservedBufferLength) {
        return new LongListDiskSegment(longsPerChunk, capacity, reservedBufferLength, fileSystemManager);
    }

    @Override
    protected LongListDiskSegment createLongList(
            final Path file, final int longsPerChunk, final long capacity, final long reservedBufferLength)
            throws IOException {
        return new LongListDiskSegment(file, longsPerChunk, capacity, reservedBufferLength, fileSystemManager);
    }

    /**
     * Provides a stream of writer-reader pairs specifically for the {@link LongListSegment}
     * implementation. The writer is always {@link LongListSegment}, and it is paired with
     * all reader implementations (heap, off-heap, disk-based, and segment-based). This
     * allows for testing whether data written by the {@link LongListSegment} can be correctly
     * read back by all supported long list implementations.
     *
     * @return a stream of argument pairs, each containing a {@link LongListSegment} writer
     *         and one of the supported reader implementations
     */
    static Stream<Arguments> longListWriterReaderPairsProvider() {
        return longListWriterBasedPairsProvider(segmentWriterFactory);
    }

    /**
     * Provides a stream of writer paired with two reader implementations for testing
     * cross-compatibility.
     * <p>
     * Used for {@link AbstractLongListTest#testUpdateMinToTheLowerEnd}
     *
     * @return a stream of arguments containing a writer and two readers.
     */
    static Stream<Arguments> longListWriterSecondReaderPairsProvider() {
        return longListWriterSecondReaderPairsProviderBase(longListWriterReaderPairsProvider());
    }

    /**
     * Provides writer-reader pairs combined with range configurations for testing.
     * <p>
     * Used for {@link AbstractLongListTest#testWriteReadRangeElement}
     *
     * @return a stream of arguments for range-based parameterized tests
     */
    static Stream<Arguments> longListWriterReaderRangePairsProvider() {
        return longListWriterReaderRangePairsProviderBase(longListWriterReaderPairsProvider());
    }

    /**
     * Provides writer-reader pairs combined with chunk offset configurations for testing.
     * <p>
     * Used for {@link AbstractLongListTest#testPersistListWithNonZeroMinValidIndex}
     * and {@link AbstractLongListTest#testPersistShrunkList}
     *
     * @return a stream of arguments for chunk offset based parameterized tests
     */
    static Stream<Arguments> longListWriterReaderOffsetPairsProvider() {
        return longListWriterReaderOffsetPairsProviderBase(longListWriterReaderPairsProvider());
    }

    @Test
    void takeoverReadsValidRange() {
        final int capacity = 1_000;
        final LongListDiskSegment list1 = new LongListDiskSegment(100, capacity, 0, fileSystemManager);
        try (list1) {
            list1.updateValidRange(capacity / 3, capacity / 2);
        }
        final Path backingFile = list1.getBackingFile();
        final LongListDiskSegment list2 = new LongListDiskSegment(backingFile, 100, capacity, 0);
        try (list2) {
            list2.takeover();
            Assertions.assertEquals(capacity / 3, list2.getMinValidIndex());
            Assertions.assertEquals(capacity / 2, list2.getMaxValidIndex());
        } finally {
            list2.delete();
        }
    }

    @Test
    void noDataBeforeTakeover() {
        final int capacity = 1_000;
        final LongListDiskSegment list1 = new LongListDiskSegment(100, capacity, 0, fileSystemManager);
        try (list1) {
            list1.updateValidRange(0, capacity - 1);
            list1.put(1, 111);
        }
        final Path backingFile = list1.getBackingFile();
        final LongListDiskSegment list2 = new LongListDiskSegment(backingFile, 100, capacity, 0);
        try (list2) {
            Assertions.assertEquals(-1, list2.get(1, -1));
            list2.takeover();
            Assertions.assertEquals(111, list2.get(1, -1));
        } finally {
            list2.delete();
        }
    }

    @Test
    void differentChunkSizeWorks() {
        final int capacity = 10_000;
        final LongListDiskSegment list1 = new LongListDiskSegment(128, capacity, 0, fileSystemManager);
        try (list1) {
            list1.updateValidRange(0, capacity - 1);
            for (int i = 0; i < capacity; i++) {
                list1.put(i, i * 2 + 1);
            }
        }
        final Path backingFile = list1.getBackingFile();
        final LongListDiskSegment list2 = new LongListDiskSegment(backingFile, 111, capacity, 0);
        try (list2) {
            list2.takeover();
            for (int i = 0; i < capacity; i++) {
                Assertions.assertEquals(i * 2 + 1, list2.get(i));
            }
        } finally {
            list2.delete();
        }
    }

    @Test
    void multipleTakeoversWork() {
        final int capacity = 1_000;
        final LongListDiskSegment list1 = new LongListDiskSegment(100, capacity, 0, fileSystemManager);
        try (list1) {
            list1.updateValidRange(0, capacity - 1);
            list1.put(1, 123);
        }
        final Path backingFile = list1.getBackingFile();
        final LongListDiskSegment list2 = new LongListDiskSegment(backingFile, 100, capacity * 2, 0);
        try (list2) {
            list2.takeover();
            Assertions.assertEquals(0, list2.getMinValidIndex());
            Assertions.assertEquals(capacity - 1, list2.getMaxValidIndex());
            Assertions.assertEquals(123, list2.get(1, -1));
            list2.updateValidRange(0, capacity * 2 - 1);
            list2.put(capacity, 234);
        }
        final LongListDiskSegment list3 = new LongListDiskSegment(backingFile, 100, capacity * 3, 0);
        try (list3) {
            list3.takeover();
            Assertions.assertEquals(0, list3.getMinValidIndex());
            Assertions.assertEquals(capacity * 2 - 1, list3.getMaxValidIndex());
            Assertions.assertEquals(123, list3.get(1, -1));
            Assertions.assertEquals(234, list3.get(capacity, -1));
            list3.updateValidRange(capacity, capacity * 3 - 1);
            list3.put(capacity, 345); // Just check it works
            list3.put(capacity * 2, 456); // Just check it works
        } finally {
            list3.delete();
        }
    }

    @Test
    void putBeforeTakeoverThrows() {
        final int capacity = 1_000;
        final LongListDiskSegment list1 = new LongListDiskSegment(100, capacity, 0, fileSystemManager);
        try (list1) {
            list1.updateValidRange(0, capacity - 1);
        }
        final Path backingFile = list1.getBackingFile();
        final LongListDiskSegment list2 = new LongListDiskSegment(backingFile, 100, capacity, 0);
        try (list2) {
            // With assertions enabled, put() throws an assertion error
            Assertions.assertThrows(AssertionError.class, () -> list2.put(0, 999));
            // Check that updateValidRange() throws
            Assertions.assertThrows(IllegalStateException.class, () -> list2.updateValidRange(0, capacity - 1));
            list2.takeover();
            Assertions.assertDoesNotThrow(() -> list2.put(0, 999));
        } finally {
            list2.delete();
        }
    }
}
