// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.Objects;
import sun.misc.Unsafe;

public class MemoryUtils {

    /**
     * Access to sun.misc.Unsafe required to close mapped byte buffers explicitly rather than
     * to rely on GC to collect them.
     */
    private static final Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Releases the mapped byte buffer.
     *
     * @param buffer the buffer to release, must not be null
     */
    public static void closeMmapBuffer(@NonNull final MappedByteBuffer buffer) {
        Objects.requireNonNull(buffer);
        UNSAFE.invokeCleaner(buffer);
    }

    /**
     * Releases the direct byte buffer.
     *
     * @param buffer the buffer to release, must not be null, must be direct
     */
    public static void closeDirectByteBuffer(@NonNull final ByteBuffer buffer) {
        Objects.requireNonNull(buffer);
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Byte buffer is not direct");
        }
        UNSAFE.invokeCleaner(buffer);
    }
}
