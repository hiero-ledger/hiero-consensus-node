// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main.model.reconnect;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.DataInput;
import java.io.InputStream;
import org.hiero.base.io.streams.SerializableDataInputStream;

/**
 * A {@link SerializableDataInputStream} that counts the number of bytes read from it and optionally decompresses
 * the data using gzip compression.
 */
public interface SyncInputStream extends DataInput, Closeable {

    /**
     * Get the byte counter that counts the number of bytes read from this stream.
     *
     * @return the {@link ByteCounter}
     */
    @NonNull
    ByteCounter byteCounter();

    /**
     * Gets this object as an {@link InputStream}.
     *
     * @return the {@link InputStream}
     */
    @NonNull
    InputStream asInputStream();
}
