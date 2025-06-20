// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.utility.MemoryUtils;

/**
 * Writes preconsensus events to a file using a {@link FileChannel}.
 */
public class PcesFileChannelWriter implements PcesFileWriter {
    /** The capacity of the ByteBuffer used to write events */
    private static final int BUFFER_CAPACITY = 1024 * 1024 * 10;
    /** The file channel for writing events */
    private final FileChannel channel;
    /** The buffer used to hold data being written to the file */
    private ByteBuffer buffer;
    /** Wraps a ByteBuffer so that the protobuf codec can write to it */
    private WritableSequentialData writableSequentialData;
    /** Tracks the size of the file in bytes */
    private int fileSize;
    /**
     * Create a new writer that writes events to a file using a {@link FileChannel}.
     *
     * @param filePath       the path to the file to write to
     * @throws IOException if an error occurs while opening the file
     */
    public PcesFileChannelWriter(@NonNull final Path filePath) throws IOException {
        this(filePath, List.of());
    }

    /**
     * Create a new writer that writes events to a file using a {@link FileChannel}.
     *
     * @param filePath       the path to the file to write to
     * @param extraOpenOptions extra flags to indicate how to open the file
     * @throws IOException if an error occurs while opening the file
     */
    public PcesFileChannelWriter(@NonNull final Path filePath, @NonNull final List<OpenOption> extraOpenOptions)
            throws IOException {
        final List<OpenOption> allOpenOptions =
                new ArrayList<>(List.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
        allOpenOptions.addAll(extraOpenOptions);
        this.channel = FileChannel.open(filePath, allOpenOptions.toArray(OpenOption[]::new));
        this.buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        this.writableSequentialData = BufferedData.wrap(buffer);
    }

    @Override
    public void writeVersion(final int version) throws IOException {
        buffer.putInt(version);
        flipWriteClear();
    }

    @Override
    public long writeEvent(@NonNull final GossipEvent event) throws IOException {
        final int size = GossipEvent.PROTOBUF.measureRecord(event);
        final boolean expandBuffer = size > buffer.capacity();
        if (expandBuffer) {
            MemoryUtils.closeDirectByteBuffer(buffer);
            buffer = ByteBuffer.allocateDirect(size);
            writableSequentialData = BufferedData.wrap(buffer);
        }
        buffer.putInt(size);
        GossipEvent.PROTOBUF.write(event, writableSequentialData);
        flipWriteClear();
        return size;
    }

    /**
     * Writes the data in the buffer to the file. This method expects that the buffer will have data that is written to
     * it. The buffer will be flipped so that it can be read from, the data will be written to the file, and the buffer
     * will be cleared so that it can be used again.
     */
    private void flipWriteClear() throws IOException {

        buffer.flip();
        final int bytesWritten = channel.write(buffer);
        fileSize += bytesWritten;
        if (bytesWritten != buffer.limit()) {
            throw new IOException(
                    "Failed to write data to file. Wrote " + bytesWritten + " bytes out of " + buffer.limit());
        }
        buffer.clear();
    }

    @Override
    public void flush() throws IOException {
        // nothing to do here
    }

    @Override
    public void sync() throws IOException {
        // benchmarks show that this has horrible performance for the channel writer (in mac-os)
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @Override
    public long fileSize() {
        return fileSize;
    }
}
