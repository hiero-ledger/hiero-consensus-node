// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.sync;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.io.counting.ByteCounter;
import org.hiero.consensus.io.counting.CounterType;
import org.hiero.consensus.io.counting.CountingOutputStream;

/**
 * A {@link SerializableDataOutputStream} that counts the number of bytes written to it and optionally compresses
 * the data using gzip compression.
 */
public class SyncOutputStream extends SerializableDataOutputStream {

    private final ByteCounter byteCounter;

    protected SyncOutputStream(@NonNull final OutputStream out, @NonNull final ByteCounter byteCounter) {
        super(out);
        this.byteCounter = byteCounter;
    }

    /**
     * Create a new {@link SyncOutputStream} that optionally compresses the data using gzip compression and
     * counts the number of bytes written to it.
     *
     * @param configuration the configuration to use to determine whether to use gzip compression
     * @param out the output stream to write to
     * @param bufferSize the buffer size to use when writing to the output stream
     * @return a new {@link SyncOutputStream}
     */
    public static SyncOutputStream createSyncOutputStream(
            @NonNull final Configuration configuration, @NonNull final OutputStream out, final int bufferSize) {

        final boolean compress = configuration.getConfigData(SocketConfig.class).gzipCompression();

        final CountingOutputStream meteredStream = new CountingOutputStream(out, CounterType.THREAD_SAFE);

        final OutputStream wrappedStream;
        if (compress) {
            wrappedStream = new DeflaterOutputStream(
                    meteredStream, new Deflater(Deflater.DEFAULT_COMPRESSION, true), bufferSize, true);
        } else {
            wrappedStream = new BufferedOutputStream(meteredStream, bufferSize);
        }

        // we write the data to the buffer first, for efficiency
        return new SyncOutputStream(wrappedStream, meteredStream.byteCounter());
    }

    /**
     * Get the byte counter that counts the number of bytes written to this stream.
     *
     * @return the {@link ByteCounter}
     */
    public ByteCounter byteCounter() {
        return byteCounter;
    }
}
