// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.common;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.nio.file.Path;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.io.counting.ByteCounter;
import org.hiero.consensus.io.counting.CounterType;
import org.hiero.consensus.io.counting.CountingOutputStream;

/**
 * Writes events to a file using an output stream.
 */
public class PcesOutputStreamFileWriter implements PcesFileWriter {
    /** The output stream to write to */
    private final SerializableDataOutputStream out;
    /** The file descriptor of the file being written to */
    private final FileDescriptor fileDescriptor;
    /** Counts the bytes written to the file */
    private final ByteCounter counter;

    /**
     * Create a new file writer.
     *
     * @param filePath the path to the file to write to
     * @throws IOException if the file cannot be opened
     */
    public PcesOutputStreamFileWriter(@NonNull final Path filePath) throws IOException {
        final FileOutputStream fileOutputStream = new FileOutputStream(filePath.toFile());
        fileDescriptor = fileOutputStream.getFD();
        final CountingOutputStream meteredStream =
                new CountingOutputStream(new BufferedOutputStream(fileOutputStream), CounterType.FAST);
        counter = meteredStream.byteCounter();
        out = new SerializableDataOutputStream(meteredStream);
    }

    @Override
    public void writeVersion(final int version) throws IOException {
        out.writeInt(version);
    }

    @Override
    public long writeEvent(@NonNull final GossipEvent event) throws IOException {
        return out.writePbjRecord(event, GossipEvent.PROTOBUF);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void sync() throws IOException {
        out.flush();
        try {
            fileDescriptor.sync();
        } catch (final SyncFailedException e) {
            throw new IOException("Failed to sync file", e);
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
}
