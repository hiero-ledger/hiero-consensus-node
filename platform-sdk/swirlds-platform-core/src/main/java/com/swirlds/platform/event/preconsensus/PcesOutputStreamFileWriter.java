// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.io.extendable.ExtendableOutputStream;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.nio.file.Path;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Writes events to a file using an output stream.
 */
public class PcesOutputStreamFileWriter implements PcesFileWriter {
    /** The output stream to write to */
    private final SerializableDataOutputStream out;
    /** The file descriptor of the file being written to */
    private final FileDescriptor fileDescriptor;
    /** Counts the bytes written to the file */
    private final CountingStreamExtension counter;
    /** Keeps stats of the writing process */
    private final PcesFileWriterStats stats;

    /**
     * Create a new file writer.
     *
     * @param filePath the path to the file to write to
     * @throws IOException if the file cannot be opened
     */
    public PcesOutputStreamFileWriter(@NonNull final Path filePath) throws IOException {
        counter = new CountingStreamExtension(false);
        final FileOutputStream fileOutputStream = new FileOutputStream(filePath.toFile());
        fileDescriptor = fileOutputStream.getFD();
        this.stats = new PcesFileWriterStats();
        out = new SerializableDataOutputStream(
                new ExtendableOutputStream(new BufferedOutputStream(fileOutputStream), counter));
    }

    @Override
    public void writeVersion(final int version) throws IOException {
        out.writeInt(version);
    }

    @Override
    public void writeEvent(@NonNull final GossipEvent event) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            out.writePbjRecord(event, GossipEvent.PROTOBUF);
        } finally {
            stats.updateWriteStats(startTime, System.currentTimeMillis(), GossipEvent.PROTOBUF.measureRecord(event));
        }
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void sync() throws IOException {
        long startTime = System.currentTimeMillis();
        out.flush();
        try {
            fileDescriptor.sync();
        } catch (final SyncFailedException e) {
            throw new IOException("Failed to sync file", e);
        } finally {
            stats.updateSyncStats(startTime, System.currentTimeMillis());
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    @Override
    public long fileSize() {
        return counter.getCount();
    }

    @Override
    public PcesFileWriterStats getStats() {
        return stats;
    }
}
