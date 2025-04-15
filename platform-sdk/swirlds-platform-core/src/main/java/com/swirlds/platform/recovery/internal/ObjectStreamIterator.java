// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;

/**
 * Iterates over objects in an object stream.
 */
public class ObjectStreamIterator<T extends SelfSerializable> implements IOIterator<T> {

    /**
     * The base input stream.
     */
    private final SerializableDataInputStream in;

    /**
     * Counts the bytes read from the stream.
     */
    private final CountingStreamExtension byteCounter;

    /**
     * Once we encounter the first IO exception, we want to report this exception any time somebody attempts to do
     * something that is not possible due to the exception.
     */
    private Exception exception;

    /**
     * The next object that will be returned by {@link #next()}, or null if no object is ready to be returned.
     */
    private T next;

    /**
     * If true, then allow for the object file to be abruptly terminated, and return the non-corrupted data at the
     * beginning of the file.
     */
    private final boolean toleratePartialFile;

    private static final int EXPECTED_OBJECT_STREAM_VERSION = 1;
    private static final int EXPECTED_EVENT_STREAM_VERSION = 5;

    /**
     * Create an iterator that reads from a file.
     *
     * @param objectStreamFile    the file to read from
     * @param toleratePartialFile if true then allow the stream file to end abruptly (possibly mid-object), and return
     *                            all objects that are complete within the stream. If false then throw if the file is
     *                            incomplete.
     * @throws FileNotFoundException if the file does not exist or can't be read
     */
    public ObjectStreamIterator(final Path objectStreamFile, final boolean toleratePartialFile) throws IOException {
        this(new BufferedInputStream(new FileInputStream(objectStreamFile.toFile())), toleratePartialFile);
    }

    /**
     * Create an iterator from an input stream.
     *
     * @param in                  the input stream
     * @param toleratePartialFile if true then allow the stream file to end abruptly (possibly mid-object), and return
     *                            all objects that are complete within the stream. If false then throw if the file is
     *                            incomplete.
     */
    public ObjectStreamIterator(final InputStream in, final boolean toleratePartialFile) throws IOException {
        this.byteCounter = new CountingStreamExtension();
        this.in = new SerializableDataInputStream(new ExtendableInputStream(in, byteCounter));
        this.toleratePartialFile = toleratePartialFile;

        try {
            final int eventStreamVersion = this.in.readInt();
            if (eventStreamVersion != EXPECTED_EVENT_STREAM_VERSION) {
                throw new IOException("unexpected event stream version " + eventStreamVersion);
            }

            final int objectStreamVersion = this.in.readInt();
            if (objectStreamVersion != EXPECTED_OBJECT_STREAM_VERSION) {
                throw new IOException("unexpected object stream version " + objectStreamVersion);
            }

        } catch (final EOFException e) {
            if (!toleratePartialFile) {
                exception = e;
                throw e;
            }
        } catch (final IOException e) {
            exception = e;
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() throws IOException {
        if (exception != null) {
            throw new IOException(exception);
        }

        if (next == null) {
            final long initialCount = byteCounter.getCount();
            try {
                next = in.readSerializable();
            } catch (final EOFException e) {
                if (byteCounter.getCount() > initialCount && !toleratePartialFile) {
                    exception = e;
                    throw e;
                }
                return false;
            } catch (final IOException e) {
                exception = e;
                throw e;
            }
        }

        return next != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T next() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        try {
            return next;
        } finally {
            next = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T peek() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return next;
    }

    /**
     * Get the number of bytes read from the stream so far.
     */
    public long getBytesRead() {
        return byteCounter.getCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        try {
            in.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
