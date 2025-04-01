// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.collections.LongList.IMPERMISSIBLE_VALUE;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.nextLong;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LongListValidRangeTest {

    public static final int MAX_LONGS = 1000;
    private AbstractLongList<?> list;

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update min valid index behavior with invalid boundaries")
    void testUpdateMinValidIndexNegativeValue(AbstractLongList<?> list) {
        this.list = list;
        assertThrows(IndexOutOfBoundsException.class, () -> list.updateValidRange(-2, maxValidIndex()));
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update min and max index to -1")
    void testUpdateMinMaxMinusOne(AbstractLongList<?> list) {
        this.list = list;
        assertDoesNotThrow(() -> list.updateValidRange(-1, -1));
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update min valid index behavior with invalid boundaries")
    void testUpdateMaxValidIndexExceedLimit(AbstractLongList<?> list) {
        this.list = list;
        assertThrows(IndexOutOfBoundsException.class, () -> list.updateValidRange(0, MAX_LONGS));
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Min/max valid indexes exceeds the list size")
    void testUpdateValidRangeOnEmptyList(AbstractLongList<?> list) {
        this.list = list;
        list.updateValidRange(0, 0);
        // it's allowed to update min valid index to the index that exceeds the list size
        assertDoesNotThrow(() -> list.updateValidRange(1, 1));
        // no chunks are created
        assertMemoryChunksNumber(0);
        // an attempt to put a value to the index that is lower than min valid index should fail
        assertThrows(AssertionError.class, () -> list.put(0, nextLong()));

        // it's allowed to update max valid index to the index that exceeds the list size
        // it shall have no effect
        assertDoesNotThrow(() -> list.updateValidRange(1, MAX_LONGS - 1));

        assertDoesNotThrow(() -> list.updateValidRange(0, MAX_LONGS - 1));
        fill(2);
        assertMemoryChunksNumber(1);

        assertDoesNotThrow(() -> list.updateValidRange(3, MAX_LONGS - 1));
        // no additional chunks created
        assertMemoryChunksNumber(1);

        assertDoesNotThrow(() -> list.updateValidRange(100, MAX_LONGS - 1));
    }

    @SuppressWarnings("resource")
    @Tag(TestComponentTags.VMAP)
    @Test
    @DisplayName("Attempt to create LongListOffHeap with too many memory chunks")
    void testInvalidMemoryChunkNumber() {
        // Valid cases
        new LongListOffHeap(1, AbstractLongList.MAX_NUM_CHUNKS, 1).close();
        new LongListOffHeap(2, AbstractLongList.MAX_NUM_CHUNKS * 2, 1).close();
        new LongListHeap(1, AbstractLongList.MAX_NUM_CHUNKS, 1).close();
        new LongListHeap(2, AbstractLongList.MAX_NUM_CHUNKS * 2, 1).close();
        new LongListDisk(1, AbstractLongList.MAX_NUM_CHUNKS, 1, CONFIGURATION)
                .resetTransferBuffer()
                .close();
        new LongListDisk(4, AbstractLongList.MAX_NUM_CHUNKS * 4, 1, CONFIGURATION)
                .resetTransferBuffer()
                .close();
        // Illegal cases, too many chunks
        assertThrows(
                IllegalArgumentException.class, () -> new LongListOffHeap(1, AbstractLongList.MAX_NUM_CHUNKS + 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongListOffHeap(8, AbstractLongList.MAX_NUM_CHUNKS * 8 + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LongListHeap(1, AbstractLongList.MAX_NUM_CHUNKS + 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongListHeap(16, AbstractLongList.MAX_NUM_CHUNKS * 16 + 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongListDisk(1, AbstractLongList.MAX_NUM_CHUNKS + 1, 1, CONFIGURATION));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongListDisk(32, AbstractLongList.MAX_NUM_CHUNKS * 32 + 1, 1, CONFIGURATION));
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update min valid index in a list with a single chunk")
    void testUpdateMinValidIndexOneChunk(AbstractLongList<?> list) {
        this.list = list;
        // one chunk: 0-2
        fill(2);
        assertHasValuesInRange(0, 2);
        assertMemoryChunksNumber(1);
        assertEquals(3, list.size());

        list.updateValidRange(1, maxValidIndex());

        assertMemoryChunksNumber(1);
        assertEmptyUpToIndex(0);
        assertHasValuesInRange(1, 2);

        // the size doesn't change
        assertEquals(3, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update max valid index in a list with a single chunk")
    void testUpdateMaxValidIndexOneChunk(AbstractLongList<?> list) {
        this.list = list;
        // one chunk: 0-2
        fill(2);
        assertHasValuesInRange(0, 2);
        assertMemoryChunksNumber(1);
        assertEquals(3, list.size());

        list.updateValidRange(0, 1);

        assertMemoryChunksNumber(1);
        assertHasValuesInRange(0, 1);
        assertEmptyFromIndex(2);
        // the size decreases
        assertEquals(2, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update valid range in a list with a single chunk to the same values")
    void testUpdateMinValidIndexOneChunkToSameValue(AbstractLongList<?> list) {
        this.list = list;
        // one chunk: 0-2
        fill(2);
        assertHasValuesInRange(0, 2);
        assertMemoryChunksNumber(1);

        list.updateValidRange(1, maxValidIndex());
        // one more time
        list.updateValidRange(1, maxValidIndex());
        assertEquals(3, list.size());

        assertMemoryChunksNumber(1);
        assertEmptyUpToIndex(0);
        assertHasValuesInRange(1, 2);
        assertEquals(3, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update min valid index behavior in a list of two chunks")
    void testUpdateMinValidIndexTwoChunks(AbstractLongList<?> list) {
        this.list = list;
        // two chunks: 0-2, 3-5
        fill(5);
        assertHasValuesInRange(0, 5);
        assertMemoryChunksNumber(2);
        assertEquals(6, list.size());

        // 4 is min valid index, 3 is a buffer. 0-2 should be freed
        list.updateValidRange(4, maxValidIndex());

        assertEmptyUpToIndex(3);
        assertHasValuesInRange(5, 5);
        // the size doesn't change
        assertEquals(6, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update max valid index behavior in a list of two chunks")
    void testUpdateMaxValidIndexTwoChunks(AbstractLongList<?> list) {
        this.list = list;
        // two chunks: 0-2, 3-5
        fill(5);
        assertHasValuesInRange(0, 5);
        assertMemoryChunksNumber(2);
        assertEquals(6, list.size());

        // 1 is max valid index, 2 is a buffer. 3-5 should be freed
        list.updateValidRange(0, 1);

        assertEmptyFromIndex(2);
        assertHasValuesInRange(0, 1);
        assertEquals(2, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update min valid index to the last index in a list of two chunks")
    void testUpdateMinValidIndexTwoChunksLastIndex(AbstractLongList<?> list) {
        this.list = list;
        // two chunks: 0-2, 3-5
        fill(5);
        assertEquals(6, list.size());

        list.updateValidRange(5, maxValidIndex());
        assertEmptyUpToIndex(4);

        assertHasValuesInRange(5, 5);
        // it never discards the last chunk because there is at least one valid index
        assertMemoryChunksNumber(1);
        assertEquals(6, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Increase and decrease min valid index in a list of two chunks")
    void testUpdateMinValidIndexTwoChunksTwice(AbstractLongList<?> list) {
        this.list = list;
        // two chunks: 0-2, 3-5
        fill(5);

        list.updateValidRange(5, maxValidIndex());
        // shrinks
        assertMemoryChunksNumber(1);
        assertEquals(6, list.size());

        list.updateValidRange(2, maxValidIndex());
        // no additional chunks created
        assertMemoryChunksNumber(1);
        assertEquals(6, list.size());

        assertEmptyUpToIndex(4);
        assertHasValuesInRange(5, 5);
        assertEquals(6, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Update max valid index to the first index in a list of two chunks")
    void testUpdateMaxValidIndexTwoChunksFirstIndex(AbstractLongList<?> list) {
        this.list = list;
        // two chunks: 0-2, 3-5
        fill(5);
        assertEquals(6, list.size());

        list.updateValidRange(0, 0);
        assertEmptyFromIndex(1);

        assertHasValuesInRange(0, 0);
        // it never discards the first chunk because there is at least one valid index
        assertMemoryChunksNumber(1);
        assertEquals(1, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Expand on write at the index lesser than min available index")
    void testExpandOnWriteBeforeMinAvailableIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8
        fill(8);
        assertMemoryChunksNumber(3);
        assertEquals(9, list.size());

        list.updateValidRange(8, maxValidIndex());
        assertMemoryChunksNumber(1);

        list.updateValidRange(0, maxValidIndex());
        list.put(0, 1);
        // it allows non-contiguous list, therefore chunk at index 1 is absent
        assertMemoryChunksNumber(2);
        assertEquals(9, list.size());

        list.put(1, 2);
        assertMemoryChunksNumber(2);
        assertEquals(IMPERMISSIBLE_VALUE, list.get(3));

        list.put(3, 4);
        assertMemoryChunksNumber(3);
        assertEquals(9, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Expand on write at the index lesser than min available index")
    void testExpandOnWriteAfterMaxAvailableIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8
        fill(8);
        assertMemoryChunksNumber(3);
        assertEquals(9, list.size());

        list.updateValidRange(0, 0);
        assertMemoryChunksNumber(1);
        assertEquals(1, list.size());

        list.updateValidRange(0, 8);
        list.put(8, 9);
        // it allows non-contiguous list, therefore chunk at index 1 is absent
        assertMemoryChunksNumber(2);
        assertEquals(9, list.size());

        list.put(1, 2);
        assertMemoryChunksNumber(2);
        assertEquals(IMPERMISSIBLE_VALUE, list.get(3));
        assertEquals(9, list.size());

        list.put(3, 4);
        assertMemoryChunksNumber(3);
        assertEquals(9, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Make sure that the chunk is not deleted if it has no data but required for "
            + "the buffer on min valid index update")
    void testKeepChunkForBuffer_minValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8
        fill(8);
        assertMemoryChunksNumber(3);
        assertEquals(9, list.size());

        list.updateValidRange(6, maxValidIndex());

        // one chunk has data, another chunk is kept for the offset
        assertMemoryChunksNumber(2);

        assertEmptyUpToIndex(5);
        assertHasValuesInRange(6, 8);
        assertEquals(9, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Make sure that the chunk is not deleted if it has no data but required for"
            + " the buffer on max valid index update")
    void testKeepChunkForBuffer_maxValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8
        fill(8);
        assertMemoryChunksNumber(3);

        list.updateValidRange(0, 2);

        // one chunk is for the data, another chunk is kept for the offset
        assertMemoryChunksNumber(2);

        assertHasValuesInRange(0, 2);
        assertEmptyFromIndex(3);
        assertEquals(3, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("longListProviderLargeBuffer")
    @DisplayName("Make sure that multiple chunks a not deleted on update of min valid index "
            + "if it has no data but required for the buffer that spans across multiple chunks")
    void testKeepChunkForLargeBuffer_minValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8, 9-11
        fill(11);
        assertMemoryChunksNumber(4);

        list.updateValidRange(10, maxValidIndex());

        // one chunk has data, another two chunks are kept for the offset
        assertMemoryChunksNumber(3);

        assertEmptyUpToIndex(9);
        assertHasValuesInRange(10, 11);
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("longListProviderLargeBuffer")
    @DisplayName("Make sure that multiple chunks a not deleted on update of max valid index"
            + "if it has no data but required for the buffer that spans across multiple chunks")
    void testKeepChunkForLargeBuffer_maxValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8, 9-11
        fill(11);
        assertMemoryChunksNumber(4);
        assertEquals(12, list.size());

        list.updateValidRange(0, 1);

        // one chunk has data, another chunk are kept for the offset
        assertMemoryChunksNumber(3);

        assertHasValuesInRange(0, 1);
        assertEmptyFromIndex(2);
        assertEquals(2, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("longListProviderIncreasedBuffer")
    @DisplayName("Make sure that the chunk is not deleted on update of min valid index"
            + "if it has no data but required for the buffer offset and the first valid chunk is partially cleaned up")
    void testKeepChunkForOffsetAndPartiallyCleanUpFirstValid_minValidIndex(AbstractLongList<?> list) {
        this.list = list;

        // three chunks: 0-2, 3-5, 6-8
        fill(8);
        assertMemoryChunksNumber(3);

        list.updateValidRange(7, maxValidIndex());

        // one chunk has data, another chunk is kept for the offset
        assertMemoryChunksNumber(2);

        assertEmptyUpToIndex(6);
        assertHasValuesInRange(7, 8);
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("longListProviderIncreasedBuffer")
    @DisplayName("Make sure that the chunk is not deleted on update of max valid index"
            + " if it has no data but required for the buffer offset and the first valid chunk is partially cleaned up")
    void testKeepChunkForOffsetAndPartiallyCleanUpFirstValid_maxValidIndex(AbstractLongList<?> list) {
        this.list = list;

        // three chunks: 0-2, 3-5, 6-8
        fill(8);
        assertMemoryChunksNumber(3);
        assertEquals(9, list.size());

        list.updateValidRange(0, 1);

        // one chunk has data, another chunk is kept for the offset
        assertMemoryChunksNumber(2);

        assertHasValuesInRange(0, 1);
        assertEmptyFromIndex(2);
        assertEquals(2, list.size());
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Make sure that if we shrink the list twice on update of min valid index, "
            + "all the chunks that have to be deleted are deleted")
    void testShrinkTwice_minValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8, 9-11
        fill(11);
        assertMemoryChunksNumber(4);

        list.updateValidRange(6, maxValidIndex());
        list.updateValidRange(10, maxValidIndex());

        // one chunk has data, another chunk is kept for the offset
        assertMemoryChunksNumber(1);

        assertEmptyUpToIndex(9);
        assertHasValuesInRange(10, 11);
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Make sure that if we shrink the list twice on update of max valid index, "
            + "all the chunks that have to be deleted are deleted")
    void testShrinkTwice_maxValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // three chunks: 0-2, 3-5, 6-8, 9-11
        fill(11);
        assertMemoryChunksNumber(4);
        assertEquals(12, list.size());

        list.updateValidRange(0, 7);
        assertEquals(8, list.size());
        list.updateValidRange(0, 1);
        assertEquals(2, list.size());

        // one chunk has data, another chunk is kept for the offset
        assertMemoryChunksNumber(1);

        assertHasValuesInRange(0, 1);
        assertEmptyFromIndex(2);
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Make sure that putIfEquals doesn't create memory chunks unnecessarily, min valid index")
    void testPutIfEqualQuitsEarly_minValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // four chunks: 0-2, 3-5, 6-8, 9-11
        fill(11);
        assertMemoryChunksNumber(4);

        list.updateValidRange(10, maxValidIndex());
        // one chunk has data, another chunk is kept for the offset
        assertMemoryChunksNumber(1);

        // it doesn't throw AssertError in this case, just returning false
        assertFalse(list.putIfEqual(1L, 2L, 42L));

        // putIfEqual doesn't result in memory block creation of indices that are lesser than
        // minValidIndex
        assertMemoryChunksNumber(1);
    }

    @Tag(TestComponentTags.VMAP)
    @ParameterizedTest
    @MethodSource("defaultLongListProvider")
    @DisplayName("Make sure that putIfEquals doesn't create memory chunks unnecessarily, max valid index")
    void testPutIfEqualQuitsEarly_maxValidIndex(AbstractLongList<?> list) {
        this.list = list;
        // four chunks: 0-2, 3-5, 6-8, 9-11
        fill(11);
        assertMemoryChunksNumber(4);

        list.updateValidRange(0, 1);
        // one chunk has data, another chunk is kept for the offset
        assertMemoryChunksNumber(1);

        // it doesn't throw AssertError in this case, just returning false
        assertFalse(list.putIfEqual(10L, 11L, 42L));

        // putIfEqual doesn't result in memory block creation of indices that are lesser than
        // minValidIndex
        assertMemoryChunksNumber(1);
    }

    public static final int NUM_LONGS_PER_CHUNK = 10000;
    public static final int INITIAL_DATA_SIZE = 10_000_000;

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @EnabledIfEnvironmentVariable(disabledReason = "Benchmark", named = "benchmark", matches = "true")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Make sure that the list releases allocated memory on update of min valid index")
    @Execution(SAME_THREAD) // to make sure that the other tests don't mess with the memory
    @ParameterizedTest
    @MethodSource("paramProvider")
    void allocateAndRelease_minValidIndex(final long newValidIndex, final long expectedAmountOfMemoryUsed, boolean left)
            throws InterruptedException {
        final BufferPoolMXBean directPool = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(v -> v.getName().equals("direct"))
                .findFirst()
                .get();
        // this is an attempt to stabilize memory footprint
        System.gc();
        Thread.sleep(10);

        final long initialMemoryAmount = directPool.getMemoryUsed();
        // 8K is the minimal memory consumption
        list = new LongListOffHeap(NUM_LONGS_PER_CHUNK, INITIAL_DATA_SIZE, 200);
        // fill with some data
        for (int i = 0; i < INITIAL_DATA_SIZE; i++) {
            list.put(i, i + 1);
        }

        final long filledListMemoryAmount = directPool.getMemoryUsed() - initialMemoryAmount;
        assertEquals(
                INITIAL_DATA_SIZE * Long.BYTES,
                filledListMemoryAmount,
                "Unexpected amount of memory consumed by the filled list");

        if (left) {
            list.updateValidRange(newValidIndex, maxValidIndex());
        } else {
            list.updateValidRange(0, newValidIndex);
        }

        final long truncatedListMemoryAmount = directPool.getMemoryUsed() - initialMemoryAmount;

        assertEquals(
                expectedAmountOfMemoryUsed,
                truncatedListMemoryAmount,
                "Unexpected amount of memory consumed by the truncated list");
    }

    static Stream<Arguments> paramProvider() {
        int memoryPerChunk = NUM_LONGS_PER_CHUNK * Long.BYTES;
        int halfElements = INITIAL_DATA_SIZE / 2;
        return Stream.of(
                // truncate the list to one element from the left, it should have only one chunk left
                Arguments.of(INITIAL_DATA_SIZE - 1, memoryPerChunk, true),
                // truncate left half of the list, it should have half + 1 chunks
                Arguments.of(halfElements, halfElements * Long.BYTES + memoryPerChunk, true),
                // truncate the list to one element from the right, it should have only one chunk left
                Arguments.of(1, memoryPerChunk, false),
                // truncate right half of the list, it should have half + 1 chunks
                Arguments.of(halfElements, halfElements * Long.BYTES + memoryPerChunk, false));
    }

    @Tag(TestComponentTags.VMAP)
    @Test
    @DisplayName("Make sure that off-heap memory consumption calculation is correct")
    void testGetOffHeapConsumptionMb_minValidIndex() {
        final int longsPerChunk = 3;
        final int reservedBufferLength = 1;
        int maxLongs = 1000;
        try (final LongListOffHeap list = new LongListOffHeap(longsPerChunk, maxLongs, reservedBufferLength)) {
            this.list = list;
            // empty list doesn't consume off-heap memory
            assertEquals(0, list.getOffHeapConsumption());

            // fill one element in the first chunk
            fill(0);
            assertEquals(24, list.getOffHeapConsumption());

            // fill three elements in the first chunk
            fill(2);
            // memory consumption remains the same
            assertEquals(24, list.getOffHeapConsumption());

            // fill 12 elements in the first 4 chunks
            fill(11);
            // memory consumption increased
            assertEquals(96, list.getOffHeapConsumption());

            // shrink the list, release three chunks
            list.updateValidRange(10, maxValidIndex());
            // memory consumption reduced to size of a single chunk
            assertEquals(24, list.getOffHeapConsumption());

            list.updateValidRange(0, maxValidIndex());
            // creating a "hole" in the list - only the first and the last chunks have data
            fill(0, false);
            // memory consumption reduced to size of a single chunk
            assertEquals(48, list.getOffHeapConsumption());
        }
    }

    @Tag(TestComponentTags.VMAP)
    @Test
    @DisplayName("Make sure that off-heap memory consumption calculation is correct")
    void testGetOffHeapConsumptionMb_maxValidIndex() {
        final int longsPerChunk = 3;
        final int reservedBufferLength = 1;
        int maxLongs = 1000;
        try (final LongListOffHeap list = new LongListOffHeap(longsPerChunk, maxLongs, reservedBufferLength)) {
            this.list = list;
            // fill 12 elements in the first 4 chunks
            fill(11);
            // shrink the list, release three chunks
            list.updateValidRange(0, 1);
            // memory consumption reduced to size of a single chunk
            assertEquals(24, list.getOffHeapConsumption());

            list.updateValidRange(0, 11);
            // creating a "hole" in the list - only the first and the last chunks have data
            list.put(11, 12);
            // memory consumption reduced to size of a single chunk
            assertEquals(48, list.getOffHeapConsumption());
        }
    }

    private void assertMemoryChunksNumber(final int expected) {
        assertEquals(expected, list.dataCopy().stream().filter(Objects::nonNull).count());
    }

    /**
     * Assert empty values up to index 'inclusive'.
     */
    private void assertEmptyUpToIndex(final int inclusive) {
        for (int i = 0; i <= inclusive; i++) {
            assertEquals(IMPERMISSIBLE_VALUE, list.get(i));
        }
    }

    private void assertEmptyFromIndex(final int inclusive) {
        for (int i = inclusive; i < list.size(); i++) {
            assertEquals(IMPERMISSIBLE_VALUE, list.get(i));
        }
    }

    /**
     * Assert values in a range (inclusive on both sides)
     */
    private void assertHasValuesInRange(final int leftInclusive, final int rightInclusive) {
        for (int i = leftInclusive; i <= rightInclusive; i++) {
            assertEquals(i + 1, list.get(i));
        }
    }

    /**
     * Fill the list with values calculated as "index + 1" up to maxIndexInclusive.
     *
     * @param maxIndexInclusive right boundary of a segment with values
     */
    void fill(final int maxIndexInclusive) {
        fill(maxIndexInclusive, true);
    }

    void fill(final int maxIndexInclusive, boolean updateValidRange) {
        if (updateValidRange) {
            list.updateValidRange(0, maxIndexInclusive);
        }
        for (int i = 0; i <= maxIndexInclusive; i++) {
            list.put(i, i + 1);
        }
    }

    static Stream<Arguments> defaultLongListProvider() {
        return longListProvider(1);
    }

    static Stream<Arguments> longListProviderLargeBuffer() {
        return longListProvider(6);
    }

    static Stream<Arguments> longListProviderIncreasedBuffer() {
        return longListProvider(2);
    }

    static Stream<Arguments> longListProvider(final int reservedBufferLength) {
        final int longsPerChunk = 3;
        return Stream.of(
                Arguments.of(new LongListOffHeap(longsPerChunk, MAX_LONGS, reservedBufferLength)),
                Arguments.of(new LongListHeap(longsPerChunk, MAX_LONGS, reservedBufferLength)),
                Arguments.of(new LongListDisk(longsPerChunk, MAX_LONGS, reservedBufferLength, CONFIGURATION)));
    }

    private long maxValidIndex() {
        return list.getMaxValidIndex();
    }

    @AfterEach
    public void cleanUp() {
        if (list != null) {
            list.close();
            if (list instanceof LongListDisk) {
                ((LongListDisk) list).resetTransferBuffer();
            }
        }
    }
}
