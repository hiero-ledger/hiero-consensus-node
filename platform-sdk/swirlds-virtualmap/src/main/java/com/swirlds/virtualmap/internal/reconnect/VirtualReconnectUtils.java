// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

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
}
