/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.virtualmap.internal.reconnect;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.io.InputStream;

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
        } else {
            leafValue = null;
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
            out.writeInt(0);
        }
    }
}
