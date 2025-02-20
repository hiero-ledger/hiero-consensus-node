// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.io;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * An OutputStream that uses {@link ByteBuffer} before writing to an underlying {@link OutputStream}
 */
public class BufferedOutputStream extends OutputStream {
    private final ByteBuffer buffer;
    private final OutputStream outputStream;

    /**
     * Creates a Writer that uses an internal {@link ByteBuffer} to buffer writes to the given {@code outputStream}.
     *
     * @param outputStream   the underlying {@link OutputStream} to write to
     * @param bufferCapacity the capacity of the buffer has to be grater than 0
     * @throws IllegalArgumentException in case {@code bufferCapacity} is less or equals to 0
     * @throws NullPointerException     in case {@code outputStream} is null
     */
    public BufferedOutputStream(@NonNull final OutputStream outputStream, final int bufferCapacity) {
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("bufferCapacity must be > than 0");
        }
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream must not be null");
        this.buffer = ByteBuffer.wrap(new byte[bufferCapacity]);
    }

    /**
     * if {@code length} is less than the remaining capacity of the buffer, buffers the {@code bytes} and eventually
     * writes it to the underlying stream. if the buffer is full or {@code length} is greater than buffers capacity,
     * writes bytes and the buffer content to the underlying output stream
     *
     * @param bytes information to write
     * @throws IOException in case there was an error writing to the underlying outputStream
     */
    @Override
    public synchronized void write(@NonNull final byte[] bytes, final int offset, final int length) throws IOException {
        internalWrite(bytes, offset, length);
    }

    /**
     * if {@code bytes} length is less than the remaining capacity of the buffer, buffers the {@code bytes} and
     * eventually writes it to the underlying stream. if the buffer is full or {@code buffer} length  is greater than
     * buffers capacity, writes bytes and the buffer content to the underlying output stream
     *
     * @param bytes information to write
     * @throws IOException in case there was an error writing to the underlying outputStream
     */
    @Override
    public synchronized void write(@NonNull final byte[] bytes) throws IOException {
        internalWrite(bytes, 0, bytes.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void write(final int b) throws IOException {
        if (buffer.remaining() >= 1) {
            buffer.put((byte) b);
        } else {
            // if request length exceeds buffer capacity,
            // flush the buffer and write the data directly
            flush();
            outputStream.write(b);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void flush() throws IOException {
        flushBuffer(buffer);
        flushDestination();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        flush();
        outputStream.close();
    }

    private void internalWrite(final @NonNull byte[] bytes, final int offset, final int length) throws IOException {
        if (length >= buffer.capacity()) {
            // if request length exceeds buffer capacity, flush the buffer and write the data directly
            flush();
            writeToDestination(bytes, offset, length);
        } else {
            if (length > buffer.remaining()) {
                flush();
            }
            buffer.put(bytes, offset, length);
        }
    }

    private void writeToDestination(final byte[] bytes, final int offset, final int length) throws IOException {
        outputStream.write(bytes, offset, length);
    }

    private void flushDestination() throws IOException {
        outputStream.flush();
    }

    private void flushBuffer(final ByteBuffer buf) throws IOException {
        buf.flip();
        try {
            if (buf.remaining() > 0) {
                writeToDestination(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
            }
        } finally {
            buf.clear();
        }
    }
}
