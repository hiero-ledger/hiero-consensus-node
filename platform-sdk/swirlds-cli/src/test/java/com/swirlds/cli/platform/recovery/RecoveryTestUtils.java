package com.swirlds.cli.platform.recovery;

import com.swirlds.cli.internal.ObjectStreamIterator;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
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

public class RecoveryTestUtils {
    /**
     * Remove the second half of a file. Updates the file on disk.
     *
     * @param file                     the file to truncate
     * @param truncateOnObjectBoundary if true then truncate the file on an exact object boundary, if false then
     *                                 truncate the file somewhere that isn't an object boundary
     * @return the number of valid objects in the truncated file
     */
    public static int truncateFile(final Path file, boolean truncateOnObjectBoundary) throws IOException {

        // Grab the raw bytes.
        final InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()));
        final byte[] bytes = in.readAllBytes();
        in.close();

        // Read objects from the stream, and count the bytes at each object boundary.
        final Map<Integer, Integer> byteBoundaries = new HashMap<>();
        final CountingStreamExtension counter = new CountingStreamExtension();
        final ExtendableInputStream countingIn = new ExtendableInputStream(new FileInputStream(file.toFile()), counter);
        final IOIterator<SelfSerializable> iterator = new ObjectStreamIterator<>(countingIn, false);
        int count = 0;
        while (iterator.hasNext()) {
            byteBoundaries.put(count, (int) counter.getCount());
            iterator.next();
            count++;
        }
        iterator.close();

        Files.delete(file);

        final int objectIndex = count / 2;
        final int truncationIndex =
                truncateOnObjectBoundary ? byteBoundaries.get(objectIndex) : byteBoundaries.get(objectIndex) - 1;

        final byte[] truncatedBytes = new byte[truncationIndex];
        for (int i = 0; i < truncatedBytes.length; i++) {
            truncatedBytes[i] = bytes[i];
        }

        final OutputStream out = new BufferedOutputStream(new FileOutputStream(file.toFile()));
        out.write(truncatedBytes);
        out.close();

        return objectIndex + (truncateOnObjectBoundary ? 1 : 0);
    }
}
