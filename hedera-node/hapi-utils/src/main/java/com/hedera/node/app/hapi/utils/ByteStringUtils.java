// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteOutput;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Protobuf related utilities shared by client and server. */
public final class ByteStringUtils {
    private static final Logger log = LogManager.getLogger(ByteStringUtils.class);

    public static ByteString wrapUnsafely(@NonNull final byte[] bytes) {
        return UnsafeByteOperations.unsafeWrap(bytes);
    }

    /**
     * This method converts a protobuf ByteString into a byte array. Optimization is done in case
     * the input is a LiteralByteString to not make a copy of the underlying array and return it as
     * is. This is okay for our purposes since we never modify the array and just directly store it
     * in the database.
     *
     * <p>If the ByteString is smaller than the estimated size to allocate an UnsafeByteOutput
     * object, copy the array regardless since we'd be allocating a similar amount of memory either
     * way.
     *
     * @param byteString to convert
     * @return bytes extracted from the ByteString
     */
    public static byte[] unwrapUnsafelyIfPossible(@NonNull final ByteString byteString) {
        if (UnsafeByteOutput.supports(byteString)) {
            return internalUnwrap(byteString, new UnsafeByteOutput());
        }
        return byteString.toByteArray();
    }

    static byte[] internalUnwrap(final ByteString byteString, final UnsafeByteOutput byteOutput) {
        try {
            UnsafeByteOperations.unsafeWriteTo(byteString, byteOutput);
            return byteOutput.bytes;
        } catch (final IOException e) {
            log.warn("Unsafe retrieval of bytes failed", e);
            return byteString.toByteArray();
        }
    }

    static class UnsafeByteOutput extends ByteOutput {
        // Size of the object header plus a compressed object reference to bytes field
        static final short SIZE = 12 + 4;
        static final Function<String, Class<?>> CLASS_BY_NAME = name -> {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        };
        private static final String SUPPORTED_NAME = ByteString.class.getName() + "$LiteralByteString";
        private static final Class<?> SUPPORTED_CLASS = CLASS_BY_NAME.apply(SUPPORTED_NAME);

        private byte[] bytes;

        @Override
        public void write(final byte value) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            this.bytes = bytes;
        }

        @Override
        public void write(final ByteBuffer value) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeLazy(final byte[] bytes, final int offset, final int length) {
            this.bytes = bytes;
        }

        @Override
        public void writeLazy(final ByteBuffer value) {
            throw new UnsupportedOperationException();
        }

        @VisibleForTesting
        byte[] getBytes() {
            return bytes;
        }

        @VisibleForTesting
        static boolean supports(final ByteString byteString) {
            return byteString.size() > UnsafeByteOutput.SIZE
                    && UnsafeByteOutput.SUPPORTED_CLASS.equals(byteString.getClass());
        }
    }

    private ByteStringUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}
