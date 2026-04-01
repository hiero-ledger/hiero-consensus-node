// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.lang.Math.toIntExact;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.MemoryUtils;

/**
 * A {@link LongList} that stores its contents off-heap via {@link MemorySegment}s backed by
 * shared {@link Arena}s. Each chunk is an independently-allocated memory segment, so the
 * "chunk" containing the value for any given index is found using modular arithmetic,
 * identical to {@link LongListOffHeap}.
 *
 * <p>This implementation replaces the {@code sun.misc.Unsafe}-based access pattern used by
 * {@link LongListOffHeap} with the standard {@link java.lang.foreign} API (JDK 22+). Each
 * chunk owns a {@link Arena#ofShared() shared arena} that allows concurrent access from any
 * thread and supports deterministic deallocation when the chunk is no longer needed.
 *
 * <p>Memory access uses a {@link VarHandle} obtained from {@link ValueLayout#JAVA_LONG},
 * providing volatile reads, volatile writes, and compare-and-set operations with the same
 * memory-ordering guarantees as the {@code Unsafe} equivalents.
 *
 * <p>To reduce memory consumption, use {@link LongList#updateValidRange(long, long)} which
 * discards memory chunks for indices outside the valid range, accounting for the
 * {@link AbstractLongList#reservedBufferSize reserved buffer}.
 *
 * <p>Per the {@link LongList} contract, this class is thread-safe for both concurrent reads
 * and writes.
 */
public final class LongListSegment extends AbstractLongList<LongListSegment.SegmentChunk> implements OffHeapUser {

    private static final Logger logger = LogManager.getLogger(LongListSegment.class);

    /**
     * A VarHandle for performing volatile long reads, volatile writes, and CAS operations
     * on a {@link MemorySegment}. Coordinates are {@code (MemorySegment, long)} where the
     * long is the byte offset. {@link ValueLayout#JAVA_LONG} uses native byte order by
     * default, matching the behavior of the Unsafe-based off-heap implementation.
     */
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();

    /**
     * Pairs a {@link MemorySegment} with the {@link Arena} that owns it. The arena
     * controls the segment's lifetime: closing the arena deterministically frees the
     * native memory and invalidates the segment.
     *
     * @param segment the off-heap memory region
     * @param arena   the arena that allocated {@code segment}
     */
    record SegmentChunk(
            @NonNull MemorySegment segment, @NonNull Arena arena) {}

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Create a new segment-based long list with the specified capacity. Number of longs per
     * chunk and reserved buffer size are read from the provided configuration.
     *
     * @param capacity      Maximum number of longs permissible for this long list
     * @param configuration Platform configuration
     */
    public LongListSegment(final long capacity, @NonNull final Configuration configuration) {
        super(capacity, configuration);
    }

    /**
     * Create a new segment-based long list with the specified chunk size, capacity, and
     * reserved buffer size.
     *
     * @param longsPerChunk      Number of longs to store in each chunk of memory allocated
     * @param capacity           Maximum number of longs permissible for this long list
     * @param reservedBufferSize Reserved buffer length before the minimal valid index
     */
    public LongListSegment(final int longsPerChunk, final long capacity, final long reservedBufferSize) {
        super(longsPerChunk, capacity, reservedBufferSize);
    }

    /**
     * Create a new segment-based long list from a file that was saved, with the specified
     * capacity. Number of longs per chunk and reserved buffer size are read from the
     * provided configuration. The file must exist.
     *
     * <p>If the list size in the file is greater than the capacity, an
     * {@link IllegalArgumentException} is thrown.
     *
     * @param path          The file to load the long list from
     * @param capacity      Maximum number of longs permissible for this long list
     * @param configuration Platform configuration
     * @throws IOException If the file doesn't exist or there was a problem reading the file
     */
    public LongListSegment(@NonNull final Path path, final long capacity, @NonNull final Configuration configuration)
            throws IOException {
        super(path, capacity, configuration);
    }

    /**
     * Create a new segment-based long list from a file that was saved, with the specified
     * chunk size, capacity, and reserved buffer size. The file must exist.
     *
     * <p>If the list size in the file is greater than the capacity, an
     * {@link IllegalArgumentException} is thrown.
     *
     * @param path               The file to load the long list from
     * @param longsPerChunk      Number of longs to store in each chunk
     * @param capacity           Maximum number of longs permissible for this long list
     * @param reservedBufferSize Reserved buffer length before the minimal valid index
     * @param configuration      Platform configuration
     * @throws IOException If the file doesn't exist or there was a problem reading the file
     */
    public LongListSegment(
            @NonNull final Path path,
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            @NonNull final Configuration configuration)
            throws IOException {
        super(path, longsPerChunk, capacity, reservedBufferSize, configuration);
    }

    // =========================================================================
    // Chunk lifecycle
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Allocates a new off-heap memory segment via a shared arena. The segment is
     * guaranteed to be zero-initialized by {@link Arena#allocate}, matching the behavior
     * of {@link java.nio.ByteBuffer#allocateDirect}.
     */
    @Override
    protected SegmentChunk createChunk() {
        final Arena arena = Arena.ofShared();
        final MemorySegment segment = arena.allocate(memoryChunkSize, Long.BYTES);
        return new SegmentChunk(segment, arena);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closes the chunk's arena, deterministically freeing native memory. Unlike NIO
     * direct buffers (which depend on GC to trigger their cleaner), {@link Arena#close()}
     * releases the backing memory immediately. After this call, any access to the chunk's
     * segment will throw {@link IllegalStateException}.
     */
    @Override
    protected void closeChunk(@NonNull final SegmentChunk chunk) {
        chunk.arena().close();
    }

    // =========================================================================
    // Data access
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Performs a volatile read of the long at the given sub-index within the chunk.
     *
     * <p>If the chunk's arena has been closed by a concurrent {@link #closeChunk} call
     * (triggered by {@link LongList#updateValidRange} or {@link #close()}), the segment
     * is no longer accessible and {@link IllegalStateException} is thrown by the
     * {@link VarHandle} access. This is a benign race: the chunk was removed from
     * {@code chunkList} because it is outside the valid range, so returning the sentinel
     * {@link LongList#IMPERMISSIBLE_VALUE} is the correct answer — identical to what
     * {@link AbstractLongList#get(long, long)} returns when the chunk slot is already
     * {@code null}.
     */
    @Override
    protected long lookupInChunk(@NonNull final SegmentChunk chunk, final long subIndex) {
        try {
            return (long) LONG_HANDLE.getVolatile(chunk.segment(), subIndex * Long.BYTES);
        } catch (final IllegalStateException e) {
            // Arena was closed concurrently — chunk is outside the valid range
            return IMPERMISSIBLE_VALUE;
        } catch (final IndexOutOfBoundsException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Index out of bounds in lookupInChunk: segment size={}, offset={}, subIndex={}",
                    chunk.segment().byteSize(),
                    subIndex * Long.BYTES,
                    subIndex,
                    e);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs a volatile write of the long at the given sub-index within the chunk.
     *
     * <p>If the chunk's arena has been closed by a concurrent {@link #closeChunk} call,
     * the write is silently dropped. This is safe because the chunk has already been
     * removed from {@code chunkList} — the index is outside the valid range, and
     * {@code AbstractLongList#putImpl} assertions (when enabled) would have caught
     * the out-of-range access before reaching this point.
     */
    @Override
    protected void putToChunk(@NonNull final SegmentChunk chunk, final int subIndex, final long value) {
        try {
            LONG_HANDLE.setVolatile(chunk.segment(), (long) subIndex * Long.BYTES, value);
        } catch (final IllegalStateException e) {
            // Arena was closed concurrently — chunk is outside the valid range, drop the write
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs a compare-and-set operation at the given sub-index within the chunk.
     *
     * <p>If the chunk's arena has been closed by a concurrent {@link #closeChunk} call,
     * the operation returns {@code false}. This is equivalent to the fast-path in
     * {@link AbstractLongList#putIfEqual(long, long, long)} that returns {@code false}
     * when the chunk slot is already {@code null} — the chunk no longer participates
     * in the valid range.
     */
    @Override
    protected boolean putIfEqual(
            @NonNull final SegmentChunk chunk, final int subIndex, final long oldValue, final long newValue) {
        try {
            return LONG_HANDLE.compareAndSet(chunk.segment(), (long) subIndex * Long.BYTES, oldValue, newValue);
        } catch (final IllegalStateException e) {
            // Arena was closed concurrently — chunk is outside the valid range
            return false;
        }
    }

    // =========================================================================
    // Partial cleanup
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Zeroes out the specified number of entries on the left or right side of the chunk
     * using {@link MemorySegment#fill(byte)}. The fill uses plain (non-volatile) memory
     * stores, so a concurrent reader may observe a partially-zeroed region — some entries
     * may still return stale values while adjacent entries already return zero. This is
     * acceptable because {@code partialChunkCleanup} is called from
     * {@link LongList#updateValidRange}, which has already moved the valid-range
     * boundaries; any stale value a reader sees is for an index that is no longer valid,
     * and the reader's own valid-range check will discard it. This is consistent with
     * the behavior of {@link LongListOffHeap}, which uses non-volatile
     * {@code MemoryUtils.setMemory} for the same operation.
     *
     * <p>If the chunk's arena has been closed concurrently (e.g. by {@link #close()}
     * racing with an in-flight {@link LongList#updateValidRange}), the cleanup is
     * silently skipped — the chunk is already deallocated, so there is nothing to zero.
     */
    @Override
    protected void partialChunkCleanup(
            @NonNull final SegmentChunk chunk, final boolean leftSide, final long entriesToCleanUp) {
        try {
            final long offset = leftSide ? 0 : (longsPerChunk - entriesToCleanUp) * Long.BYTES;
            final long bytes = entriesToCleanUp * Long.BYTES;
            chunk.segment().asSlice(offset, bytes).fill((byte) 0);
        } catch (final IllegalStateException e) {
            // Arena was closed concurrently — chunk is already deallocated, nothing to clean
        }
    }

    // =========================================================================
    // File I/O
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Reads chunk data from a file channel into a newly allocated segment chunk. The
     * segment's backing memory is exposed as a {@link ByteBuffer} view for
     * {@link FileChannel} compatibility, avoiding an extra copy.
     */
    @Override
    protected SegmentChunk readChunkData(
            @NonNull final FileChannel fileChannel, final int chunkIndex, final int startIndex, final int endIndex)
            throws IOException {
        final SegmentChunk chunk = createChunk();
        // Get a ByteBuffer view of the segment — backed by the same native memory, no copy
        final ByteBuffer buf = chunk.segment().asByteBuffer().order(ByteOrder.nativeOrder());
        readDataIntoBuffer(fileChannel, chunkIndex, startIndex, endIndex, buf);
        // Reset position/limit — segment access is always by absolute offset
        buf.clear();
        return chunk;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes all chunk data to the file channel. Each chunk's {@link MemorySegment}
     * data is bulk-copied into a reusable, arena-independent direct {@link ByteBuffer}
     * before being written to the channel. This decouples the write from the chunk's
     * arena lifecycle: even if a concurrent {@link #closeChunk} invalidates the segment
     * after the copy, the write proceeds safely from the detached buffer.
     *
     * <p>If a chunk's arena is closed during the copy itself (narrow race between
     * {@code chunkList.get(i)} and the bulk copy), the chunk is treated as empty and
     * zeros are written — consistent with how null chunk slots are handled.
     */
    @Override
    protected void writeLongsData(@NonNull final FileChannel fc) throws IOException {
        final int totalNumOfChunks = calculateNumberOfChunks(size());
        final long currentMinValidIndex = minValidIndex.get();
        final int firstChunkWithDataIndex = toIntExact(currentMinValidIndex / longsPerChunk);

        // A single reusable direct buffer, decoupled from any arena. We copy chunk data
        // into this buffer before writing, so FileChannel.write never touches arena-scoped
        // memory directly.
        final ByteBuffer writeBuf = ByteBuffer.allocateDirect(memoryChunkSize).order(ByteOrder.nativeOrder());
        final MemorySegment writeBufSegment = MemorySegment.ofBuffer(writeBuf);
        try {
            for (int i = firstChunkWithDataIndex; i < totalNumOfChunks; i++) {
                writeBuf.clear();

                final SegmentChunk segChunk = chunkList.get(i);
                if (segChunk != null) {
                    try {
                        // Bulk copy from the arena-scoped segment into the detached buffer.
                        // After this copy completes, writeBuf holds a snapshot of the chunk
                        // data that is independent of the arena's lifetime.
                        MemorySegment.copy(segChunk.segment(), 0, writeBufSegment, 0, memoryChunkSize);
                    } catch (final IllegalStateException e) {
                        // Arena was closed between chunkList.get and the copy — treat as empty
                        writeBufSegment.fill((byte) 0);
                    }
                } else {
                    writeBufSegment.fill((byte) 0);
                }

                if (i == firstChunkWithDataIndex) {
                    final int firstValidIndexInChunk = toIntExact(currentMinValidIndex % longsPerChunk);
                    writeBuf.position(firstValidIndexInChunk * Long.BYTES);
                } else {
                    writeBuf.position(0);
                }

                if (i == (totalNumOfChunks - 1)) {
                    final long bytesWrittenSoFar = (long) memoryChunkSize * i;
                    final long remainingBytes = size() * Long.BYTES - bytesWrittenSoFar;
                    writeBuf.limit(toIntExact(remainingBytes));
                } else {
                    writeBuf.limit(memoryChunkSize);
                }

                MerkleDbFileUtils.completelyWrite(fc, writeBuf);
            }
        } finally {
            MemoryUtils.closeDirectByteBuffer(writeBuf);
        }
    }

    // =========================================================================
    // Off-heap measurement
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Measures the approximate amount of off-heap memory consumed by counting non-null
     * chunks. The result may deviate by one chunk size if a chunk is concurrently added
     * or removed during the measurement.
     */
    @Override
    public long getOffHeapConsumption() {
        int nonEmptyChunkCount = 0;
        final int chunkListSize = chunkList.length();
        for (int i = 0; i < chunkListSize; i++) {
            if (chunkList.get(i) != null) {
                nonEmptyChunkCount++;
            }
        }
        return (long) nonEmptyChunkCount * memoryChunkSize;
    }
}
