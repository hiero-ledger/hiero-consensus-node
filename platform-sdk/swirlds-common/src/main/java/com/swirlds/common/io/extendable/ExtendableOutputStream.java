// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * An {@link OutputStream} where data passes through it and provides methods to do extra work with that data.
 */
public class ExtendableOutputStream extends OutputStream {
    private final OutputStream stream;
    private final OutputStreamExtension extension;

    /**
     * Extend an output stream.
     *
     * @param stream
     * 		a stream to extend
     * @param extensions
     * 		zero or more extensions
     * @return an extended stream
     */
    public static OutputStream extendOutputStream(
            final OutputStream stream, final OutputStreamExtension... extensions) {

        if (extensions == null) {
            return stream;
        }
        OutputStream s = stream;
        for (final OutputStreamExtension extension : extensions) {
            s = new ExtendableOutputStream(s, extension);
        }
        return s;
    }

    /**
     * Create a new output stream.
     *
     * @param stream
     * 		the stream to wrap
     * @param extension
     * 		an extension
     */
    public ExtendableOutputStream(final OutputStream stream, final OutputStreamExtension extension) {
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.extension = Objects.requireNonNull(extension, "extension must not be null");
        extension.init(stream);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final int b) throws IOException {
        extension.write(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final byte[] bytes, final int offset, final int length) throws IOException {
        extension.write(bytes, offset, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() throws IOException {
        stream.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        try {
            stream.close();
        } finally {
            extension.close();
        }
    }
}
