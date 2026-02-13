// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.sync;

import static org.hiero.consensus.io.extendable.ExtendableInputStream.extendInputStream;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.io.extendable.extensions.CountingStreamExtension;

public class SyncInputStream extends SerializableDataInputStream {

    /** The maximum number of tips allowed per node. */
    private static final int MAX_TIPS_PER_NODE = 1000;

    private final CountingStreamExtension syncByteCounter;

    private SyncInputStream(InputStream in, CountingStreamExtension syncByteCounter) {
        super(in);
        this.syncByteCounter = syncByteCounter;
    }

    public static SyncInputStream createSyncInputStream(
            @NonNull final Configuration configuration, @NonNull final InputStream in, final int bufferSize) {

        final CountingStreamExtension syncCounter = new CountingStreamExtension();

        final boolean compress = configuration.getConfigData(SocketConfig.class).gzipCompression();

        final InputStream meteredStream = extendInputStream(in, syncCounter);

        final InputStream wrappedStream;
        if (compress) {
            wrappedStream = new InflaterInputStream(meteredStream, new Inflater(true), bufferSize);
        } else {
            wrappedStream = new BufferedInputStream(meteredStream, bufferSize);
        }

        return new SyncInputStream(wrappedStream, syncCounter);
    }

    public CountingStreamExtension getSyncByteCounter() {
        return syncByteCounter;
    }

    /**
     * Read the other node's tip hashes
     *
     * @throws IOException is a stream exception occurs
     */
    public List<Hash> readTipHashes(final int numberOfNodes) throws IOException {
        return readSerializableList(numberOfNodes * MAX_TIPS_PER_NODE, false, Hash::new);
    }
}
