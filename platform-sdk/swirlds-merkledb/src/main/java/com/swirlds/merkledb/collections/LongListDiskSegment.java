// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static java.lang.Math.toIntExact;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.file.FileSystemManager;

///
/// {@link LongList} implementation that stores all index entries in a file and maps them
/// into memory using {@link MemorySegment}s. This implementation is different from
/// {@link LongListDisk} in multiple aspects. First, {@link LongListDisk} doesn't consume
/// Java heap or off-heap memory. All index lookups are actual disk reads. This class maps
/// all data into memory, so index lookups are memory reads. This means this class is a
/// real memory consumer. Second, data layout on disk is different from {@link LongListDisk}'s.
/// The backing file used by this class reflects index structure: a long value at offset 0
/// is index value 0, a long value at offset 8 is value 1, and so on. Chunks are sequential
/// parts of the file mapped to memory: the first chunk is mapped at offset 0, the second
/// chunk is at offset memoryChunkSize, and so on. For some indexes, e.g. path to leaf
/// records, elements in range 0 to first leaf path are outside the leaf path range. The
/// backing file still contains these elements with zero values, but the corresponding
/// chunks are not mapped into memory, and overall memory consumption is about the same as
/// in {@link LongListSegment}.
///
/// This long disk implementation is to support fast version upgrades. During upgrades, the old
/// process is shutdown, the new process is started, and MerkleDb indices must be transferred
/// from the former to the latter. If indices are written to disk and then read back in the new
/// process, it's too slow. Bypassing loading indices at all (like {@link LongListDisk}) is a
/// bad option, either, because performance is not acceptable. This class uses a different
/// approach. All index elements are stored in a file, but the file is mapped into memory (in
/// both processes). Reading and updating list elements are never disk operations, it's all
/// done in memory, so performance is comparable to {@link LongListSegment} or {@link
/// LongListOffHeap}.
///
/// Version upgrade flow is as follows. The new process builds a MerkleDb instance on top of
/// existing data and index files. No MerkleDb requests are expected in the new process at
/// this point yet. The old process closes all indices using {@link #close()}. This method
/// doesn't delete the backing index file, so it can still be used in the new process. Then
/// in the new process {@link #takeover()} is called for every long list index. At this point,
/// the lists update current valid ranges from the backing file and map the file to memory.
/// After {@link #takeover()}, long lists can be used both for reads and writes as normal.
///
/// Chunk management in this implementation is similar to what's in {@link LongListDisk}:
/// a chunk is a pair (MemorySegment, Arena), so chunks can be created and closed individually.
/// However, chunks in this class are always at fixed offsets, while {@link LongListDisk}
/// moves chunks around the backing file as they are created and closed.
///
public final class LongListDiskSegment extends AbstractLongList<LongListDiskSegment.SegmentChunk>
        implements OffHeapUser {

    private static final String DEFAULT_FILE_NAME = "LongListDiskSegment.ll";

    ///
    /// A VarHandle for performing volatile long reads, volatile writes, and CAS operations
    /// on a {@link MemorySegment}.
    ///
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();

    ///
    /// Header size. The header is two longs to store the min and the max valid index in
    /// this list. The header is read in {@link #takeover()} and written in {@link #close()}.
    ///
    private static final long HEADER_SIZE = Long.BYTES /* min valid index */ + Long.BYTES /* max valid index */;

    ///
    /// Pairs a {@link MemorySegment} with the {@link Arena} that owns it. The arena
    /// controls the segment's lifetime: closing the arena deterministically frees the
    /// native memory and invalidates the segment.
    ///
    public record SegmentChunk(
            @NonNull MemorySegment segment, @NonNull Arena arena) {}

    private Path backingFile;

    private FileChannel fileChannel;
    private FileLock fileLock;

    private SegmentChunk header;

    /// A helper flag to make sure close() can be called multiple times.
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ///
    /// Create a new long list with the specified capacity. Number of longs per
    /// chunk and reserved buffer size are read from the provided configuration.
    ///
    /// @param capacity Maximum number of longs permissible for this long list
    /// @param configuration Platform configuration
    ///
    public LongListDiskSegment(
            final long capacity,
            @NonNull final Configuration configuration,
            @NonNull final FileSystemManager fileSystemManager) {
        super(capacity, configuration);
        initNewFileChannel(DEFAULT_FILE_NAME, fileSystemManager);
    }

    ///
    /// Create a new long list with the specified capacity, number of longs per
    /// chunk, and reserved buffer size.
    ///
    /// @param longsPerChunk Number of longs per single chunk
    /// @param capacity Maximum number of longs permissible for this long list
    /// @param reservedBufferSize Reserved buffer size when the long list is shrunk
    ///
    public LongListDiskSegment(
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            @NonNull final FileSystemManager fileSystemManager) {
        super(longsPerChunk, capacity, reservedBufferSize);
        initNewFileChannel(DEFAULT_FILE_NAME, fileSystemManager);
    }

    ///
    /// Create a new long list from a snapshot file, with the specified capacity.
    /// Number of longs per chunk and reserved buffer size are read from the
    /// provided configuration. The file must exist.
    ///
    /// <p>If the list size in the file is greater than the capacity, an
    /// {@link IllegalArgumentException} is thrown.
    ///
    /// @param snapshotFile The file to load the long list from
    /// @param capacity Maximum number of longs permissible for this long list
    /// @param configuration Platform configuration
    /// @param fileSystemManager File system manager to use for resolving temp files
    /// @throws IOException If the file doesn't exist or there was a problem reading the file
    ///
    public LongListDiskSegment(
            @NonNull final Path snapshotFile,
            final long capacity,
            @NonNull final Configuration configuration,
            @NonNull final FileSystemManager fileSystemManager)
            throws IOException {
        super(capacity, configuration);
        initNewFileChannel(snapshotFile.getFileName().toString(), fileSystemManager);
        loadFromFile(snapshotFile);
    }

    ///
    /// Create a new long list from a snapshot file, with the specified capacity,
    /// number of longs per chunk, and reserved buffer size. The file must exist.
    ///
    /// <p>If the list size in the file is greater than the capacity, an
    /// {@link IllegalArgumentException} is thrown.
    ///
    /// @param snapshotFile The file to load the long list from
    /// @param longsPerChunk Number of longs per single chunk
    /// @param capacity Maximum number of longs permissible for this long list
    /// @param reservedBufferSize Reserved buffer size when the long list is shrunk
    /// @param fileSystemManager File system manager to use for resolving temp files
    /// @throws IOException If the file doesn't exist or there was a problem reading the file
    ///
    public LongListDiskSegment(
            @NonNull final Path snapshotFile,
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            @NonNull final FileSystemManager fileSystemManager)
            throws IOException {
        super(longsPerChunk, capacity, reservedBufferSize);
        initNewFileChannel(snapshotFile.getFileName().toString(), fileSystemManager);
        loadFromFile(snapshotFile);
    }

    ///
    /// Create a new long list from the specified backing file (supposedly, created in
    /// another process) with specified capacity. Number of longs per chunk and reserved
    /// buffer capacity are read from the configuration.
    ///
    /// The specified backing file is not copied anywhere, it will be used by this long
    /// list directly to store list values. The created long list should not be used till
    /// {@link #takeover()} is called.
    ///
    public LongListDiskSegment(
            @NonNull final Path backingFile, final long capacity, @NonNull final Configuration configuration) {
        super(capacity, configuration);
        initExistingFileChannel(backingFile);
    }

    ///
    /// Create a new long list from the specified backing file (supposedly, created in
    /// another process) with specified capacity, number of longs per chunk, and reserved
    /// buffer capacity.
    ///
    /// The specified backing file is not copied anywhere, it will be used by this long
    /// list directly to store list values. The created long list should not be used till
    /// {@link #takeover()} is called.
    ///
    public LongListDiskSegment(
            @NonNull final Path backingFile,
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize) {
        super(longsPerChunk, capacity, reservedBufferSize);
        initExistingFileChannel(backingFile);
    }

    private void initNewFileChannel(
            @NonNull final String fileName, @NonNull final FileSystemManager fileSystemManager) {
        assert backingFile == null;
        try {
            backingFile = fileSystemManager.resolveNewTemp(fileName);
            if (Files.exists(backingFile)) {
                throw new IOException("File already exists: " + backingFile);
            }
            fileChannel = FileChannel.open(
                    backingFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            fileLock = fileChannel.tryLock();
            if (fileLock == null) {
                throw new IOException("Failed to lock the backing index file: " + backingFile.getFileName());
            }
            header = map(0, HEADER_SIZE);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void initExistingFileChannel(@NonNull final Path backingFile) {
        assert this.backingFile == null;
        try {
            this.backingFile = backingFile;
            if (!Files.exists(backingFile)) {
                throw new IOException("File does not exist: " + backingFile);
            }
            fileChannel = FileChannel.open(backingFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
            // File lock and header will be initialized in takeover()
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void takeover() {
        try {
            if (fileLock != null) {
                // Double takeover? Should throw an exception?
                return;
            }
            // Acquire the lock
            fileLock = fileChannel.tryLock();
            if (fileLock == null) {
                throw new IOException("Failed to lock the backing index file: " + backingFile.getFileName());
            }
            // Read valid range from the header
            header = map(0, HEADER_SIZE);
            minValidIndex.set((long) LONG_HANDLE.getVolatile(header.segment(), 0));
            maxValidIndex.set((long) LONG_HANDLE.getVolatile(header.segment(), Long.BYTES));
            size.set(maxValidIndex.get() + 1);
            if (size() == 0) {
                // Empty list. Not likely, but possible
                return;
            }
            final int totalNumOfChunks = calculateNumberOfChunks(size());
            final int firstChunkWithDataIndex = toIntExact(minValidIndex.get() / longsPerChunk);
            for (int i = firstChunkWithDataIndex; i < totalNumOfChunks; i++) {
                assert chunkList.get(i) == null;
                final SegmentChunk chunk = createChunk(i);
                chunkList.set(i, chunk);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ///
    /// Closes this long list. The backing file is preserved on disk, so it can be
    /// used by other processes, if needed.
    ///
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            // Already closed
            return;
        }
        try {
            // Write valid range to the header
            if (header != null) {
                LONG_HANDLE.setVolatile(header.segment(), 0, minValidIndex.get());
                LONG_HANDLE.setVolatile(header.segment(), Long.BYTES, maxValidIndex.get());
                header.arena().close();
                header = null;
            }
            // Release (unmap) all chunks
            super.close();
            // Close the file channel
            fileChannel.close();
            // The file is not deleted here, this is LongListDiskSegment contract
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // For testing purposes only
    void delete() {
        if (!closed.get()) {
            throw new IllegalStateException("The index hasn't been closed yet");
        }
        try {
            Files.delete(backingFile);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // For testing purposes
    Path getBackingFile() {
        return backingFile;
    }

    // =========================================================================
    // Chunk lifecycle
    // =========================================================================

    private SegmentChunk map(final long offset, final long length) throws IOException {
        final Arena arena = Arena.ofShared();
        final MemorySegment segment = fileChannel.map(MapMode.READ_WRITE, offset, length, arena);
        return new SegmentChunk(segment, arena);
    }

    ///
    /// Long list chunks are created in {@link AbstractLongList#createOrGetChunk(long)}. By default,
    /// that method calls {@link #createChunk()} to create a new chunk, and then sets this chunk to the
    /// chunk list. It works for other long list implementations where all chunks are similar, and any
    /// chunk can be assigned any index. This is not the case for {@link LongListDiskSegment}. Each chunk
    /// is a memory mapped segment that reflects a particular offset in the backing file. This is why
    /// this method is overridden here to use private {@link #createChunk(int)}, and {@link
    /// #createChunk()} is never used.
    ///
    @Override
    protected SegmentChunk createOrGetChunk(final long newIndex) {
        size.getAndUpdate(oldSize -> newIndex >= oldSize ? (newIndex + 1) : oldSize);
        final int chunkIndex = toIntExact(newIndex / longsPerChunk);
        final SegmentChunk result = chunkList.get(chunkIndex);
        if (result == null) {
            final SegmentChunk newChunk = createChunk(chunkIndex);
            // Put the new chunk to the list, if it's not set yet, if it is - release the chunk
            // immediately and use the one from the list
            final SegmentChunk oldChunk = chunkList.compareAndExchange(chunkIndex, null, newChunk);
            if (oldChunk == null) {
                return newChunk;
            } else {
                closeChunk(newChunk);
                return oldChunk;
            }
        } else {
            return result;
        }
    }

    ///
    /// This method must never be called. See the comment to {@link #createOrGetChunk(long)}.
    ///
    @Override
    protected SegmentChunk createChunk() {
        throw new UnsupportedOperationException("LongListDiskSegment.createChunk() must never be called");
    }

    private SegmentChunk createChunk(final int chunkIndex) {
        try {
            return map(HEADER_SIZE + (long) chunkIndex * memoryChunkSize, memoryChunkSize);
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
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
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs a volatile write of the long at the given sub-index within the chunk.
     *
     * <p>Unlike {@link #lookupInChunk} and {@link #putIfEqual}, this method does
     * <b>not</b> catch {@link IllegalStateException} from a closed arena. In production,
     * {@code put()} and {@code updateValidRange()} are always called sequentially on the
     * same thread (e.g. within {@code writeLeavesToPathToKeyValue} or {@code writeHashes}),
     * so the arena cannot be closed between {@code createOrGetChunk} and this call. If an
     * {@code IllegalStateException} occurs here, it indicates a bug in higher-level
     * coordination (e.g. a concurrent {@code close()} during flush) and must not be
     * silently swallowed — a lost write is silent data corruption.
     */
    @Override
    protected void putToChunk(final SegmentChunk chunk, final int subIndex, final long value) {
        LONG_HANDLE.setVolatile(chunk.segment(), (long) subIndex * Long.BYTES, value);
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
        return LONG_HANDLE.compareAndSet(chunk.segment(), (long) subIndex * Long.BYTES, oldValue, newValue);
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
        final SegmentChunk chunk = createChunk(chunkIndex);
        // Get a ByteBuffer view of the segment — backed by the same native memory, no copy
        final ByteBuffer buf = chunk.segment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        readDataIntoBuffer(fileChannel, chunkIndex, startIndex, endIndex, buf);
        return chunk;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes all chunk data to the file channel. Each chunk's {@link MemorySegment}
     * is exposed as a {@link ByteBuffer} view via {@link MemorySegment#asByteBuffer()}
     * for {@link FileChannel} compatibility. For null chunk slots (sparse regions), a
     * pre-allocated zero-filled buffer is written instead.
     *
     * <p>This method runs exclusively during snapshot, which is sequenced after flush
     * completion by the virtual pipeline. No concurrent {@link #closeChunk} can
     * invalidate a chunk's arena during this operation.
     */
    @Override
    protected void writeLongsData(@NonNull final FileChannel fc) throws IOException {
        final int totalNumOfChunks = calculateNumberOfChunks(size());
        final long currentMinValidIndex = minValidIndex.get();
        final int firstChunkWithDataIndex = toIntExact(currentMinValidIndex / longsPerChunk);

        // A zero-filled buffer for null chunk slots. Heap-allocated — no arena needed.
        final ByteBuffer emptyBuffer = ByteBuffer.allocate(memoryChunkSize);

        for (int i = firstChunkWithDataIndex; i < totalNumOfChunks; i++) {
            final SegmentChunk segChunk = chunkList.get(i);
            final ByteBuffer buf;
            if (segChunk != null) {
                buf = segChunk.segment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            } else {
                emptyBuffer.clear();
                buf = emptyBuffer;
            }

            if (i == firstChunkWithDataIndex) {
                final int firstValidIndexInChunk = toIntExact(currentMinValidIndex % longsPerChunk);
                buf.position(firstValidIndexInChunk * Long.BYTES);
            } else {
                buf.position(0);
            }

            if (i == (totalNumOfChunks - 1)) {
                final long bytesWrittenSoFar = (long) memoryChunkSize * i;
                final long remainingBytes = size() * Long.BYTES - bytesWrittenSoFar;
                buf.limit(toIntExact(remainingBytes));
            } else {
                buf.limit(memoryChunkSize);
            }

            MerkleDbFileUtils.completelyWrite(fc, buf);
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
