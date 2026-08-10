// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.common;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.PbjWriter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes preconsensus events to a file using a {@link FileChannel}.
 */
public class PcesFileChannelWriter implements PcesFileWriter {
    private final FileChannel channel;
    private final PbjWriter pbjWriter;
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
        channel = FileChannel.open(filePath, allOpenOptions.toArray(OpenOption[]::new));
        pbjWriter = new PbjWriter(Channels.newOutputStream(channel));
    }

    @Override
    public void writeVersion(final int version) throws IOException {
        pbjWriter.writeInt(version);
        pbjWriter.flush();
    }

    @Override
    public long writeEvent(@NonNull final GossipEvent event) throws IOException {
        final int size = GossipEvent.PROTOBUF.measureRecord(event);
        pbjWriter.writeInt(size);
        GossipEvent.PROTOBUF.write(event, pbjWriter);
        pbjWriter.flush();
        return size;
    }

    @Override
    public void flush() throws IOException {
        pbjWriter.flush();
    }

    @Override
    public void sync() throws IOException {
        // benchmarks show that this has horrible performance for the channel writer (in mac-os)
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        pbjWriter.close();
    }

    @Override
    public long fileSize() {
        try {
            return channel.position();
        } catch (Exception ex) {
            return 0;
        }
    }
}
