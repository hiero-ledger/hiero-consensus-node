// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.sync;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.io.counting.ByteCounter;
import org.hiero.consensus.io.counting.CounterType;
import org.hiero.consensus.io.counting.CountingInputStream;

/**
 * A {@link SerializableDataInputStream} that counts the number of bytes read from it and optionally decompresses
 * the data using gzip compression.
 */
public class SyncInputStream extends SerializableDataInputStream {

    private final ByteCounter byteCounter;

    private SyncInputStream(@NonNull final InputStream in, @NonNull final ByteCounter byteCounter) {
        super(in);
        this.byteCounter = byteCounter;
    }

    /**
     * Create a new {@link SyncInputStream} that optionally decompresses the data using gzip compression and
     * counts the number of bytes read from it.
     *
     * @param configuration the configuration to use to determine whether to use gzip compression
     * @param in the input stream to read from
     * @param bufferSize the buffer size to use when reading from the input stream
     * @return a new {@link SyncInputStream}
     */
    public static SyncInputStream createSyncInputStream(
            @NonNull final Configuration configuration, @NonNull final InputStream in, final int bufferSize) {

        final boolean compress = configuration.getConfigData(SocketConfig.class).gzipCompression();

        final CountingInputStream meteredStream = new CountingInputStream(in, CounterType.THREAD_SAFE);

        final InputStream wrappedStream;
        if (compress) {
            wrappedStream = new InflaterInputStream(meteredStream, new Inflater(true), bufferSize);
        } else {
            wrappedStream = new BufferedInputStream(meteredStream, bufferSize);
        }

        return new SyncInputStream(wrappedStream, meteredStream.byteCounter());
    }

    /**
     * Get the byte counter that counts the number of bytes read from this stream.
     *
     * @return the {@link ByteCounter}
     */
    public ByteCounter byteCounter() {
        return byteCounter;
    }
}
