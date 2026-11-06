// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

class LongListSegmentTest extends AbstractLongListTest<LongListSegment> {

    @Override
    protected LongListSegment createLongList(long capacity, MerkleDbConfig config) {
        return new LongListSegment(capacity, config);
    }

    @Override
    protected LongListSegment createLongList(
            final int longsPerChunk, final long capacity, final long reservedBufferLength) {
        return new LongListSegment(longsPerChunk, capacity, reservedBufferLength);
    }

    @Override
    protected LongListSegment createLongList(
            final Path file, final int longsPerChunk, final long capacity, final long reservedBufferLength)
            throws IOException {
        return new LongListSegment(file, longsPerChunk, capacity, reservedBufferLength);
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
    @DisplayName("A copy has the same contents and valid range as the source")
    void copyHasSameContents() {
        final int longsPerChunk = 100;
        try (final LongListSegment source = new LongListSegment(longsPerChunk, 10_000, 10)) {
            source.updateValidRange(0, 2_999);
            for (int i = 0; i < 3_000; i++) {
                source.put(i, i + 1);
            }
            // Drop a few chunks on the left, so the copy starts with null chunks
            source.updateValidRange(1_234, 2_999);

            try (final LongListSegment copy = new LongListSegment(source)) {
                assertEquals(source.size(), copy.size());
                assertEquals(source.getMinValidIndex(), copy.getMinValidIndex());
                assertEquals(source.getMaxValidIndex(), copy.getMaxValidIndex());
                assertEquals(source.getLongsPerChunk(), copy.getLongsPerChunk());
                assertEquals(source.capacity(), copy.capacity());
                assertEquals(source.getOffHeapConsumption(), copy.getOffHeapConsumption());
                for (int i = 0; i < 3_000; i++) {
                    assertEquals(source.get(i), copy.get(i), "Value mismatch at index " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("A copy doesn't share memory with the source")
    void copyIsIndependentOfSource() {
        final LongListSegment source = new LongListSegment(100, 10_000, 10);
        source.updateValidRange(0, 999);
        for (int i = 0; i < 1_000; i++) {
            source.put(i, i + 1);
        }
        try (final LongListSegment copy = new LongListSegment(source)) {
            // Changes to the copy aren't visible in the source
            copy.put(5, 42);
            assertEquals(6, source.get(5));
            // Changes to the source aren't visible in the copy
            source.put(7, 43);
            assertEquals(8, copy.get(7));
            // The copy stays readable after the source is closed
            source.close();
            for (int i = 0; i < 1_000; i++) {
                assertEquals(i == 5 ? 42 : i + 1, copy.get(i), "Value mismatch at index " + i);
            }
        }
    }

    @Test
    @DisplayName("A copy of an empty list is empty")
    void copyOfEmptyList() {
        try (final LongListSegment source = new LongListSegment(100, 10_000, 10);
                final LongListSegment copy = new LongListSegment(source)) {
            assertEquals(0, copy.size());
            assertEquals(-1, copy.getMinValidIndex());
            assertEquals(-1, copy.getMaxValidIndex());
            assertEquals(0, copy.getOffHeapConsumption());
        }
    }
}
