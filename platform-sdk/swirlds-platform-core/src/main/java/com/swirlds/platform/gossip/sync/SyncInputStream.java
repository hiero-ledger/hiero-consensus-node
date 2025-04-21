// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.sync;

import static com.swirlds.common.io.extendable.ExtendableInputStream.extendInputStream;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
import com.swirlds.platform.network.SocketConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;

public class SyncInputStream extends SerializableDataInputStream {

    /** The maximum number of tips allowed per node. */
    private static final int MAX_TIPS_PER_NODE = 1000;

    private final CountingStreamExtension syncByteCounter;

    private SyncInputStream(InputStream in, CountingStreamExtension syncByteCounter) {
        super(in);
        this.syncByteCounter = syncByteCounter;
    }

    public static SyncInputStream createSyncInputStream(
            @NonNull final PlatformContext platformContext, @NonNull final InputStream in, final int bufferSize) {

        final CountingStreamExtension syncCounter = new CountingStreamExtension();

        final boolean compress = platformContext
                .getConfiguration()
                .getConfigData(SocketConfig.class)
                .gzipCompression();

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
