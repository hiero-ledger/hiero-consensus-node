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
import org.hiero.consensus.main.model.reconnect.ByteCounter;
import org.hiero.consensus.io.counting.CounterType;
import org.hiero.consensus.io.counting.CountingOutputStream;
import org.hiero.consensus.main.model.reconnect.SyncOutputStream;

/**
 * A {@link SerializableDataOutputStream} that counts the number of bytes written to it and optionally compresses
 * the data using gzip compression.
 */
public class SyncOutputStreamImpl extends SerializableDataOutputStream implements SyncOutputStream {

    private final ByteCounter connectionByteCounter;

    protected SyncOutputStreamImpl(@NonNull final OutputStream out, @NonNull final ByteCounter connectionByteCounter) {
        super(out);
        this.connectionByteCounter = connectionByteCounter;
    }

    /**
     * Create a new {@link SyncOutputStreamImpl} that optionally compresses the data using gzip compression and
     * counts the number of bytes written to it.
     *
     * @param configuration the configuration to use to determine whether to use gzip compression
     * @param out the output stream to write to
     * @param bufferSize the buffer size to use when writing to the output stream
     * @return a new {@link SyncOutputStreamImpl}
     */
    public static SyncOutputStreamImpl createSyncOutputStream(
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
        return new SyncOutputStreamImpl(wrappedStream, meteredStream.byteCounter());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ByteCounter connectionByteCounter() {
        return connectionByteCounter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputStream asOutputStream() {
        return this;
    }
}
