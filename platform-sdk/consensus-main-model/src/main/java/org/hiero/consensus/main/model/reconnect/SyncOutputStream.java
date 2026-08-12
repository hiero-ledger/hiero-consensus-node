// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main.model.reconnect;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A {@link SerializableDataOutputStream} that counts the number of bytes written to it and optionally compresses
 * the data using gzip compression.
 */
public interface SyncOutputStream extends DataOutput, Closeable {
    /**
     * Get the connection byte counter that counts the number of bytes written to this stream.
     *
     * @return the {@link ByteCounter}
     */
    @NonNull
    ByteCounter connectionByteCounter();

    /**
     * Gets this object as an {@link OutputStream}.
     *
     * @return the {@link OutputStream}
     */
    @NonNull
    OutputStream asOutputStream();

    /**
     * Flushes this {@link SyncOutputStream}.
     */
    void flush() throws IOException;
}
