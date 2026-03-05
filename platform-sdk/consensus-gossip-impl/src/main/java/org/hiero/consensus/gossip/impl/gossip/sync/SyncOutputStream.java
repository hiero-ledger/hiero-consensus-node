// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.sync;

import static org.hiero.consensus.io.extendable.ExtendableOutputStream.extendOutputStream;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.io.extendable.extensions.CountingStreamExtension;

public class SyncOutputStream extends SerializableDataOutputStream {
    private final CountingStreamExtension syncByteCounter;
    private final CountingStreamExtension connectionByteCounter;

    protected SyncOutputStream(
            @NonNull final OutputStream out,
            @NonNull final CountingStreamExtension syncByteCounter,
            @NonNull final CountingStreamExtension connectionByteCounter) {
        super(out);
        this.syncByteCounter = syncByteCounter;
        this.connectionByteCounter = connectionByteCounter;
    }

    public static SyncOutputStream createSyncOutputStream(
            @NonNull final Configuration configuration, @NonNull final OutputStream out, final int bufferSize) {
        final CountingStreamExtension syncByteCounter = new CountingStreamExtension();
        final CountingStreamExtension connectionByteCounter = new CountingStreamExtension();

        final boolean compress = configuration.getConfigData(SocketConfig.class).gzipCompression();

        final OutputStream meteredStream = extendOutputStream(out, connectionByteCounter);

        final OutputStream wrappedStream;
        if (compress) {
            wrappedStream = new DeflaterOutputStream(
                    meteredStream, new Deflater(Deflater.DEFAULT_COMPRESSION, true), bufferSize, true);
        } else {
            wrappedStream = new BufferedOutputStream(meteredStream, bufferSize);
        }

        // we write the data to the buffer first, for efficiency
        return new SyncOutputStream(wrappedStream, syncByteCounter, connectionByteCounter);
    }

    public CountingStreamExtension getSyncByteCounter() {
        return syncByteCounter;
    }

    public CountingStreamExtension getConnectionByteCounter() {
        return connectionByteCounter;
    }
}
