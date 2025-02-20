// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions;

import com.swirlds.common.io.extendable.InputStreamExtension;
import com.swirlds.common.io.extendable.OutputStreamExtension;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Implements boilerplate that allows a simple extension definition be used for both input and output streams.
 * Although compatible with multiple stream types, each instance of this class should only be used with a single stream.
 */
public abstract class AbstractStreamExtension implements InputStreamExtension, OutputStreamExtension {

    private InputStream inputStream;
    private OutputStream outputStream;

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final InputStream baseStream) {
        inputStream = baseStream;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final OutputStream baseStream) {
        outputStream = baseStream;
    }

    /**
     * Notifies the stream that this byte has passed through it
     *
     * @param aByte
     * 		the byte that has passed through the stream
     */
    protected abstract void newByte(int aByte) throws IOException;

    /**
     * Notifies the stream that a number of bytes have passed through it
     *
     * @param bytes
     * 		the byte buffer containing the data
     * @param offset
     * 		the start offset in array bytes at which the data is
     * @param length
     * 		the number of bytes from the offset
     */
    protected abstract void newBytes(byte[] bytes, int offset, int length) throws IOException;

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        final int aByte = inputStream.read();
        if (aByte != -1) {
            newByte(aByte);
        }
        return aByte;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(final byte[] bytes, final int offset, final int length) throws IOException {

        final int count = inputStream.read(bytes, offset, length);
        if (count != -1) {
            newBytes(bytes, offset, count);
        }
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] readNBytes(final int length) throws IOException {
        final byte[] bytes = inputStream.readNBytes(length);
        newBytes(bytes, 0, bytes.length);
        return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readNBytes(final byte[] bytes, final int offset, final int length) throws IOException {

        final int count = inputStream.readNBytes(bytes, offset, length);
        newBytes(bytes, offset, count);
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final int b) throws IOException {
        outputStream.write(b);
        newByte(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final byte[] bytes, final int offset, final int length) throws IOException {
        outputStream.write(bytes, offset, length);
        newBytes(bytes, offset, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        // Override this if the extension needs to be closed
    }
}
