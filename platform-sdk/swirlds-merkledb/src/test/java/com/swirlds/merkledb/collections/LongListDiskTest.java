// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;

import com.swirlds.config.api.Configuration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

public class LongListDiskTest extends AbstractLongListTest<LongListDisk> {

    @Override
    protected LongListDisk createLongList(long capacity, Configuration config) {
        return new LongListDisk(capacity, config);
    }

    @Override
    protected LongListDisk createLongList(
            final int longsPerChunk, final long capacity, final long reservedBufferLength) {
        return new LongListDisk(longsPerChunk, capacity, reservedBufferLength, CONFIGURATION);
    }

    @Override
    protected LongListDisk createLongList(
            final Path file, final int longsPerChunk, final long capacity, final long reservedBufferLength)
            throws IOException {
        return new LongListDisk(file, longsPerChunk, capacity, reservedBufferLength, CONFIGURATION);
    }

    /**
     * Provides a stream of writer-reader pairs specifically for the {@link LongListDisk} implementation.
     * The writer is always {@link LongListDisk}, and it is paired with three reader implementations
     * (heap, off-heap, and disk-based). This allows for testing whether data written by the
     * {@link LongListDisk} can be correctly read back by all supported long list implementations.
     * <p>
     * This method builds on {@link AbstractLongListTest#longListWriterBasedPairsProvider} to generate
     * the specific writer-reader combinations for the {@link LongListDisk} implementation.
     *
     * @return a stream of argument pairs, each containing a {@link LongListDisk} writer
     *         and one of the supported reader implementations
     */
    static Stream<Arguments> longListWriterReaderPairsProvider() {
        return longListWriterBasedPairsProvider(diskWriterFactory);
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
     * Provides writer-reader pairs combined with chunk offset configurations (second set) for testing.
     * <p>
     * Used for {@link AbstractLongListTest#testPersistListWithNonZeroMinValidIndex}
     * and {@link AbstractLongListTest#testPersistShrunkList}
     *
     * @return a stream of arguments for chunk offset based parameterized tests
     */
    static Stream<Arguments> longListWriterReaderOffsetPairsProvider() {
        return longListWriterReaderOffsetPairsProviderBase(longListWriterReaderPairsProvider());
    }
}
