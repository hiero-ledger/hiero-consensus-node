// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;
import java.nio.Buffer;
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

    /** Offset of the {@code java.nio.Buffer#address} field. */
    private static final long BYTE_BUFFER_ADDRESS_FIELD_OFFSET;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            throw new InternalError(e);
        }
        try {
            BYTE_BUFFER_ADDRESS_FIELD_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("address"));
        } catch (final NoSuchFieldException | SecurityException | IllegalArgumentException e) {
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

    public static long getLongVolatile(@NonNull final ByteBuffer buffer, final long offset) {
        final long address = bufferAddress(buffer);
        return UNSAFE.getLongVolatile(null, address + offset);
    }

    public static void putLongVolatile(@NonNull final ByteBuffer buffer, final long offset, final long value) {
        final long address = bufferAddress(buffer);
        UNSAFE.putLongVolatile(null, address + offset, value);
    }

    public static boolean compareAndSwapLong(
            @NonNull final ByteBuffer buffer, final long offset, final long expected, final long value) {
        final long address = bufferAddress(buffer);
        return UNSAFE.compareAndSwapLong(null, address + offset, expected, value);
    }

    public static void setMemory(
            @NonNull final ByteBuffer buffer, final long offset, final long len, final byte value) {
        final long address = bufferAddress(buffer);
        UNSAFE.setMemory(address + offset, len, value);
    }

    /**
     * Get the address at which the underlying buffer storage begins.
     *
     * @param buffer that wraps the underlying storage.
     * @return the memory address at which the buffer storage begins.
     */
    private static long bufferAddress(@NonNull final Buffer buffer) {
        Objects.requireNonNull(buffer);
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("buffer.isDirect() must be true");
        }
        return UNSAFE.getLong(buffer, BYTE_BUFFER_ADDRESS_FIELD_OFFSET);
    }
}
