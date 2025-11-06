// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;

import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.IndexedObject;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The aim for a DataFileReader is to facilitate fast highly concurrent random reading of items from
 * a data file. It is designed to be used concurrently from many threads.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message DataFile {
 *
 *     // File index in its file collection
 *     uint32 index = 1;
 *
 *     // Creation timestamp, seconds
 *     uint64 creationDateSeconds = 2;
 *
 *     // Creation timestamp, nanos
 *     uint32 creationDateNanos = 3;
 *
 *     // Items count
 *     uint64 itemsCount = 4;
 *
 *     // Data item version. May not be needed
 *     uint64 itemVersion = 5;
 *
 *     // Data items
 *     repeated bytes items = 11;
 * }
 * </pre>
 */
public final class DataFileReader implements AutoCloseable, Comparable<DataFileReader>, IndexedObject {

    private static final ThreadLocal<ByteBuffer> BUFFER_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<BufferedData> BUFFEREDDATA_CACHE = new ThreadLocal<>();

    private final MerkleDbConfig dbConfig;

    /** Max number of file channels to use for reading */
    private final int maxFileChannels;

    /**
     * When a data file reader is created, a single file channel is open to read data from the
     * file. This channel is used by all threads. Number of threads currently reading data is
     * tracked in {@link #fileChannelsInUse}. When the number of threads per opened file channel
     * exceeds this threshold, a new file channel is open, unless there are {@link #maxFileChannels}
     * channels are already opened.
     */
    private final int threadsPerFileChannel;

    /**
     * A single data file reader may use multiple file channels. Previously, a single file channel
     * was used, and it resulted in unnecessary locking in FileChannelImpl.readInternal(), when
     * the number of threads working with the channel in parallel was high. Now a single file
     * channel is open in the constructor, and additioinal file channels up to {@link #maxFileChannels}
     * are opened as needed
     */
    private final AtomicReferenceArray<FileChannel> fileChannels;

    /** Number of currently opened file channels */
    private final AtomicInteger fileChannelsCount = new AtomicInteger(0);
    /** Number of file channels currently in use by all threads working with this data file reader */
    private final AtomicInteger fileChannelsInUse = new AtomicInteger(0);

    /** Indicates whether this file reader is open */
    private final AtomicBoolean open = new AtomicBoolean(true);
    /** The path to the file on disk */
    private final Path path;
    /** The metadata for this file read from the footer */
    private final DataFileMetadata metadata;
    /** A flag for if the underlying file is fully written and ready to be compacted. */
    private final AtomicBoolean fileCompleted = new AtomicBoolean(false);

    /**
     * The size of this file in bytes, cached as need it often. This size is updated in {@link
     * #setFileCompleted()}, which is called for existing files right after the reader is created,
     * and for newly created files right after they are fully written and available to compact.
     */
    private final AtomicLong fileSizeBytes = new AtomicLong(0);

    /**
     * Open an existing data file, reading the metadata from the file
     *
     * @param dbConfig MerkleDb config
     * @param path the path to the data file
     */
    public DataFileReader(final MerkleDbConfig dbConfig, final Path path) throws IOException {
        this(dbConfig, path, DataFileMetadata.readFromFile(path));
    }

    /**
     * Open an existing data file, using the provided metadata
     *
     * @param dbConfig MerkleDb config
     * @param path the path to the data file
     * @param metadata the file's metadata to save loading from file
     */
    DataFileReader(final MerkleDbConfig dbConfig, final Path path, final DataFileMetadata metadata) throws IOException {
        this.dbConfig = dbConfig;
        maxFileChannels = dbConfig.maxFileChannelsPerFileReader();
        threadsPerFileChannel = dbConfig.maxThreadsPerFileChannel();
        fileChannels = new AtomicReferenceArray<>(maxFileChannels);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(
                    "Tried to open a non existent data file [" + path.toAbsolutePath() + "].");
        }
        this.path = path;
        this.metadata = metadata;
        openNewFileChannel(0);
    }

    /**
     * Returns if this file is completed and ready to be compacted.
     *
     * @return if true the file is completed (read only and ready to compact)
     */
    public boolean isFileCompleted() {
        return fileCompleted.get();
    }

    /**
     * Marks the reader as completed, so it can be included into future compactions. If the reader
     * is created for an existing file, it's usually marked as completed immediately. If the reader
     * is created for a new file, which is still being written in a different thread, it's marked as
     * completed right after the file is fully written and the writer is closed.
     */
    void setFileCompleted() {
        try {
            fileSizeBytes.set(fileChannels.get(0).size());
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to update data file reader size", e);
        } finally {
            fileCompleted.set(true);
        }
    }

    /**
     * Get file index, the index is an ordered integer identifying the file in a set of files.
     *
     * @return file index
     */
    public int getIndex() {
        return metadata.getIndex();
    }

    /**
     * Get the metadata of this data file.
     *
     * @return file metadata
     */
    public DataFileMetadata getMetadata() {
        return metadata;
    }

    /**
     * Get the path to this data file.
     *
     * @return file path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Create an iterator to iterate over the data items in this data file. It opens its own file
     * handle so can be used in a separate thread. It must therefore be closed when you are finished
     * with it.
     *
     * @return new data item iterator
     * @throws IOException if there was a problem creating a new DataFileIterator
     */
    public DataFileIterator createIterator() throws IOException {
        return new DataFileIterator(dbConfig, path, metadata);
    }

    /**
     * Read data item from file at dataLocation.
     *
     * @param dataLocation data item location, which combines data file index and offset in the file
     * @return deserialized data item
     * @throws IOException If there was a problem reading from data file
     * @throws ClosedChannelException if the data file was closed
     */
    public BufferedData readDataItem(final long dataLocation) throws IOException {
        final long byteOffset = DataFileCommon.byteOffsetFromDataLocation(dataLocation);
        return read(byteOffset);
    }

    /**
     * Get the size of this file in bytes. This method should only be called for files available to
     * merging (compaction), i.e. after they are fully written.
     *
     * @return file size in bytes
     */
    public long getSize() {
        return fileSizeBytes.get();
    }

    /** Equals for use when comparing in collections, based on matching file paths */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DataFileReader that = (DataFileReader) o;
        return path.equals(that.getPath());
    }

    /** hashCode for use when comparing in collections, based on file path */
    @Override
    public int hashCode() {
        return path.hashCode();
    }

    /** Compares this Data File to another based on creation date and index */
    @Override
    public int compareTo(@NonNull final DataFileReader o) {
        Objects.requireNonNull(o);
        if (this == o) {
            return 0;
        }
        final int res = metadata.getCreationDate().compareTo(o.getMetadata().getCreationDate());
        return (res != 0)
                ? res
                : Integer.compare(metadata.getIndex(), o.getMetadata().getIndex());
    }

    /** ToString for debugging */
    @Override
    public String toString() {
        return Integer.toString(metadata.getIndex());
    }

    // For testing purpose
    int getMaxFileChannels() {
        return maxFileChannels;
    }

    int getThreadsPerFileChannel() {
        return threadsPerFileChannel;
    }

    /**
     * Get if the DataFile is open for reading.
     *
     * @return True if file is open for reading
     */
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() throws IOException {
        if (!open.compareAndSet(true, false)) {
            return;
        }

        for (int i = 0; i < maxFileChannels; i++) {
            final FileChannel fileChannel = fileChannels.getAndSet(i, null);
            if (fileChannel != null) {
                fileChannel.close();
            }
        }
    }

    // =================================================================================================================
    // Private methods

    /**
     * Opens a new file channel for reading the file, if the total number of channels opened is
     * less than {@link #maxFileChannels}. This method is safe to call from multiple threads.
     *
     * @param index Index of the new file channel. If greater or equal to {@link
     *                            #maxFileChannels}, no new channel is opened
     * @throws IOException
     *      If an I/O error occurs
     */
    private void openNewFileChannel(final int index) throws IOException {
        if (index >= maxFileChannels) {
            return;
        }
        final FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ);
        if (fileChannels.compareAndSet(index, null, fileChannel)) {
            fileChannelsCount.incrementAndGet();
        } else {
            fileChannel.close();
        }
    }

    /**
     * Replaces a closed file channel at a given index in {@link #fileChannels} with a new one.
     * This method is safe to be called from multiple threads. If a channel is closed, and two
     * threads are calling this method to replace it with a new channel, only one of them will
     * proceed, while the channel opened in the other thread will be closed immediately and
     * not used any further.
     *
     * <p>If the data file reader is closed, the file channel for the specified index is null.
     * This method does nothing in this case.
     *
     * @param index File channel index in {@link #fileChannels}
     * @param closedChannel Closed file channel to replace
     * @throws IOException
     *      If an I/O error occurs
     */
    private void reopenFileChannel(final int index, final FileChannel closedChannel) throws IOException {
        assert index < maxFileChannels;
        assert !closedChannel.isOpen();
        // It may happen that `fileChannels` collection is updated by another thread to null for this
        // index, for example, when the file is deleted during compaction. This is a valid scenario,
        // the call below does nothing in this case
        final FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ);
        if (!fileChannels.compareAndSet(index, closedChannel, fileChannel)) {
            fileChannel.close();
        }
    }

    /**
     * Returns an index of an opened file channel to read data and increments the lease count.
     * Opens a new file channel, if possible, when the lease count per channel is greater than
     * {@link #threadsPerFileChannel}.
     *
     * @return An index of a file channel to read data
     * @throws IOException
     *      If an I/O exception occurs
     */
    int leaseFileChannel() throws IOException {
        int count = fileChannelsCount.get();
        final int inUse = fileChannelsInUse.incrementAndGet();
        // Although openNewFileChannel() is thread safe, it makes sense to check the count here.
        // Since the channels are never closed (other than when the data file reader is closed),
        // it's safe to check count against MAX_FILE_CHANNELS
        if ((inUse / count > threadsPerFileChannel) && (count < maxFileChannels)) {
            openNewFileChannel(count);
            count = fileChannelsCount.get();
        }
        return inUse % count;
    }

    /**
     * Decreases the number of opened file channels in use by one.
     */
    void releaseFileChannel() {
        fileChannelsInUse.decrementAndGet();
    }

    /**
     * Read bytesToRead bytes of data from the file starting at byteOffsetInFile unless we reach the
     * end of file. If we reach the end of file then returned buffer's limit will be set to the
     * number of bytes read and be less than bytesToRead.
     *
     * @param byteOffsetInFile Offset to start reading at
     * @return ByteBuffer containing read data. This is a reused per thread buffer, so you can use
     *     it till your thread calls read again.
     * @throws IOException if there was a problem reading
     * @throws ClosedChannelException if the file was closed
     */
    private BufferedData read(final long byteOffsetInFile) throws IOException {
        // Buffer size to read data item tag and size. If the whole item is small and
        // fits into this buffer, there is no need to make an extra file read
        final int PRE_READ_BUF_SIZE = 2048;
        ByteBuffer readBB = BUFFER_CACHE.get();
        BufferedData readBuf = BUFFEREDDATA_CACHE.get();
        if (readBuf == null) {
            readBB = ByteBuffer.allocate(PRE_READ_BUF_SIZE);
            BUFFER_CACHE.set(readBB);
            readBuf = BufferedData.wrap(readBB);
            BUFFEREDDATA_CACHE.set(readBuf);
        }
        // Try a few times. It's very unlikely (other than in tests) that a thread is
        // interrupted more than once in short period of time, so 3 retries should be enough
        for (int retries = 3; retries > 0; retries--) {
            final int fcIndex = leaseFileChannel();
            final FileChannel fileChannel = fileChannels.get(fcIndex);
            if (fileChannel == null) {
                // On rare occasions, if we have a race condition with compaction, the file channel
                // may be closed. We need to return null, so that the caller can retry with a new reader
                return null;
            }
            try {
                // Fill the byte buffer from the channel
                readBB.clear();
                readBB.limit(PRE_READ_BUF_SIZE); // No need to read more than that for a header
                int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, readBB, byteOffsetInFile);
                if (isFileCompleted() && (bytesRead != Math.min(PRE_READ_BUF_SIZE, getSize() - byteOffsetInFile))) {
                    throw new IOException("Failed to read all bytes: toread="
                            + Math.min(PRE_READ_BUF_SIZE, getSize() - byteOffsetInFile) + " read=" + bytesRead
                            + " file=" + getIndex() + " off=" + byteOffsetInFile);
                }
                // Then read the tag and size from the read buffer, since it's wrapped over the byte buffer
                readBuf.reset();
                final int tag = readBuf.getVarInt(0, false); // tag
                if (tag
                        != ((FIELD_DATAFILE_ITEMS.number() << TAG_FIELD_OFFSET)
                                | ProtoConstants.WIRE_TYPE_DELIMITED.ordinal())) {
                    throw new IOException(
                            "Unknown data item tag: tag=" + tag + " file=" + getIndex() + " off=" + byteOffsetInFile);
                }
                final int sizeOfTag = ProtoWriterTools.sizeOfUnsignedVarInt32(tag);
                final int size = readBuf.getVarInt(sizeOfTag, false);
                final int sizeOfSize = ProtoWriterTools.sizeOfUnsignedVarInt32(size);
                // Check if the whole data item is already read in the header
                if (bytesRead >= sizeOfTag + sizeOfSize + size) {
                    readBuf.position(sizeOfTag + sizeOfSize);
                    readBuf.limit(sizeOfTag + sizeOfSize + size);
                    return readBuf;
                }
                // Otherwise read it separately
                if (readBB.capacity() <= size) {
                    readBB = ByteBuffer.allocate(size);
                    BUFFER_CACHE.set(readBB);
                    readBuf = BufferedData.wrap(readBB);
                    BUFFEREDDATA_CACHE.set(readBuf);
                } else {
                    readBB.position(0);
                    readBB.limit(size);
                }
                bytesRead = MerkleDbFileUtils.completelyRead(
                        fileChannel, readBB, byteOffsetInFile + sizeOfTag + sizeOfSize);
                if (bytesRead != size) {
                    throw new IOException("Failed to read all bytes: toread=" + size + " read=" + bytesRead + " file="
                            + getIndex() + " off=" + byteOffsetInFile);
                }
                readBuf.position(0);
                readBuf.limit(bytesRead);
                return readBuf;
            } catch (final ClosedByInterruptException e) {
                // If the thread and the channel are interrupted, propagate it to the callers
                throw e;
            } catch (final ClosedChannelException e) {
                // This exception may be thrown, if the channel was closed, because a different
                // thread reading from the channel was interrupted. Re-create the file channel
                // and retry
                reopenFileChannel(fcIndex, fileChannel);
            } finally {
                releaseFileChannel();
            }
        }
        throw new IOException("Failed to read from file, file channel keeps getting closed");
    }

    // Testing support

    int getFileChannelsCount() {
        return fileChannelsCount.get();
    }
}
