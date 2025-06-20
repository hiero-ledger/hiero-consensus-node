// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.sync;

import static com.swirlds.common.io.extendable.ExtendableOutputStream.extendOutputStream;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
import com.swirlds.platform.network.SocketConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

public class SyncOutputStream extends SerializableDataOutputStream {
    private final CountingStreamExtension syncByteCounter;
    private final CountingStreamExtension connectionByteCounter;
    private final AtomicReference<Instant> requestSent;

    protected SyncOutputStream(
            OutputStream out, CountingStreamExtension syncByteCounter, CountingStreamExtension connectionByteCounter) {
        super(out);
        this.syncByteCounter = syncByteCounter;
        this.connectionByteCounter = connectionByteCounter;
        this.requestSent = new AtomicReference<>(null);
    }

    public static SyncOutputStream createSyncOutputStream(
            @NonNull final PlatformContext platformContext, @NonNull final OutputStream out, final int bufferSize) {
        CountingStreamExtension syncByteCounter = new CountingStreamExtension();
        CountingStreamExtension connectionByteCounter = new CountingStreamExtension();

        final boolean compress = platformContext
                .getConfiguration()
                .getConfigData(SocketConfig.class)
                .gzipCompression();

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

    /**
     * Write to the {@link SyncOutputStream} the hashes of the tip events from this node's shadow graph
     *
     * @throws IOException iff the {@link SyncOutputStream} throws
     */
    public void writeTipHashes(final List<Hash> tipHashes) throws IOException {
        writeSerializableList(tipHashes, false, true);
    }
}
