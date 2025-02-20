// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * An object that extends the functionality of an {@link InputStream}.
 */
public interface InputStreamExtension extends Closeable {

    /**
     * Initialize the stream extension.
     *
     * @param baseStream
     * 		the base stream that is being extended
     */
    void init(final InputStream baseStream);

    /**
     * This method is called when the {@link InputStream#read()} is invoked on the underlying stream.
     * This method is required to eventually call {@link InputStream#read()} on the base stream.
     *
     * @return the value returned by {@link InputStream#read()}
     * @throws IOException
     * 		if there is a problem with the read
     */
    int read() throws IOException;

    /**
     * This method is called when the {@link InputStream#read(byte[], int, int)} is invoked on the underlying stream.
     * This method is required to eventually call {@link InputStream#read(byte[], int, int)} on the base stream.
     *
     * @param bytes
     * 		the array where bytes should be written
     * @param offset
     * 		the offset in the byte array where bytes should be written
     * @param length
     * 		the number of bytes to read
     * @return the value returned by {@link InputStream#read(byte[], int, int)}
     * @throws IOException
     * 		if there is a problem with the read
     */
    int read(byte[] bytes, int offset, int length) throws IOException;

    /**
     * This method is called when the {@link InputStream#readNBytes(int)} is invoked on the underlying stream.
     * This method is required to eventually call {@link InputStream#readNBytes(int)} on the base stream.
     *
     * @param length
     * 		the number of bytes to read
     * @return the value returned by {@link InputStream#readNBytes(int)}
     * @throws IOException
     * 		if there is a problem with the read
     */
    byte[] readNBytes(int length) throws IOException;

    /**
     * This method is called when the {@link InputStream#readNBytes(byte[], int, int)} is invoked on the underlying
     * stream. This method is required to eventually call {@link InputStream#readNBytes(byte[], int, int)} on the
     * base stream.
     *
     * @param offset
     * 		the offset in the byte array where bytes should be written
     * @param length
     * 		the number of bytes to read
     * @return the value returned by {@link InputStream#readNBytes(byte[], int, int)}
     * @throws IOException
     * 		if there is a problem with the read
     */
    int readNBytes(byte[] bytes, int offset, int length) throws IOException;
}
