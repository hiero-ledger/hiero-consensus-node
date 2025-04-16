// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.io.InputStream;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A class with a set of utility methods used during virtual map reconnects.
 */
public class VirtualReconnectUtils {

    /**
     * Reads bytes from an input stream to an array, until array length bytes are read, or EOF
     * is encountered.
     *
     * @param in the input stream to read from
     * @param dst the byte array to read to
     * @return the total number of bytes read
     * @throws IOException if an exception occurs while reading
     */
    public static int completelyRead(final InputStream in, final byte[] dst) throws IOException {
        int totalBytesRead = 0;
        while (totalBytesRead < dst.length) {
            final int bytesRead = in.read(dst, totalBytesRead, dst.length - totalBytesRead);
            if (bytesRead < 0) {
                // Reached EOF
                break;
            }
            totalBytesRead += bytesRead;
        }
        return totalBytesRead;
    }

    public static VirtualLeafBytes readLeafRecord(final SerializableDataInputStream in) throws IOException {
        final long leafPath = in.readLong();
        final int leafKeyLen = in.readInt();
        final byte[] leafKeyBytes = new byte[leafKeyLen];
        in.readFully(leafKeyBytes, 0, leafKeyLen);
        final Bytes leafKey = Bytes.wrap(leafKeyBytes);
        final int leafValueLen = in.readInt();
        final Bytes leafValue;
        if (leafValueLen > 0) {
            final byte[] leafValueBytes = new byte[leafValueLen];
            in.readFully(leafValueBytes, 0, leafValueLen);
            leafValue = Bytes.wrap(leafValueBytes);
        } else if (leafValueLen == 0) {
            leafValue = Bytes.EMPTY;
        } else {
            leafValue = Bytes.EMPTY;
        }
        return new VirtualLeafBytes(leafPath, leafKey, leafValue);
    }

    public static void writeLeafRecord(final SerializableDataOutputStream out, final VirtualLeafBytes leaf)
            throws IOException {
        out.writeLong(leaf.path());
        final Bytes key = leaf.keyBytes();
        out.writeInt(Math.toIntExact(key.length()));
        key.writeTo(out);
        final Bytes value = leaf.valueBytes();
        if (value != null) {
            out.writeInt(Math.toIntExact(value.length()));
            value.writeTo(out);
        } else {
            out.writeInt(-1);
        }
    }
}
