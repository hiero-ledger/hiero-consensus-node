// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.recovery.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.hiero.base.io.SelfSerializable;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.io.counting.CounterType;
import org.hiero.consensus.io.counting.CountingInputStream;

final class StreamFileTestUtils {

    private StreamFileTestUtils() {}

    /**
     * Remove the second half of a file. Updates the file on disk.
     *
     * @param file                     the file to truncate
     * @param truncateOnObjectBoundary if true then truncate the file on an exact object boundary, if false then
     *                                 truncate the file somewhere that isn't an object boundary
     * @return the number of valid objects in the truncated file
     */
    public static int truncateFile(final Path file, boolean truncateOnObjectBoundary) throws IOException {

        final InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()));
        final byte[] bytes = in.readAllBytes();
        in.close();

        final Map<Integer, Integer> byteBoundaries = new HashMap<>();
        final CountingInputStream countingIn =
                new CountingInputStream(new FileInputStream(file.toFile()), CounterType.THREAD_SAFE);
        final IOIterator<SelfSerializable> iterator = new ObjectStreamIterator<>(countingIn, false);
        int count = 0;
        while (iterator.hasNext()) {
            byteBoundaries.put(count, (int) countingIn.byteCounter().getCount());
            iterator.next();
            count++;
        }
        iterator.close();

        Files.delete(file);

        final int objectIndex = count / 2;
        final int truncationIndex =
                truncateOnObjectBoundary ? byteBoundaries.get(objectIndex) : byteBoundaries.get(objectIndex) - 1;

        final byte[] truncatedBytes = new byte[truncationIndex];
        System.arraycopy(bytes, 0, truncatedBytes, 0, truncatedBytes.length);

        final OutputStream out = new BufferedOutputStream(new FileOutputStream(file.toFile()));
        out.write(truncatedBytes);
        out.close();

        return objectIndex + (truncateOnObjectBoundary ? 1 : 0);
    }
}
