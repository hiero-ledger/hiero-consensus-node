// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import org.hiero.consensus.io.counting.internal.FastByteCounter;
import org.hiero.consensus.io.counting.internal.ModifiableByteCounter;
import org.hiero.consensus.io.counting.internal.ThreadSafeByteCounter;

/**
 * An {@link InputStream} that counts the number of bytes read from it. The count can be retrieved
 * at any time using the {@link #byteCounter()} method.
 */
public class CountingInputStream extends InputStream {

    private final InputStream inputStream;
    private final ModifiableByteCounter countingResult;

    /**
     * Constructs a {@code CountingInputStream}
     *
     * @param inputStream the base {@link InputStream}
     * @param counterType the {@link CounterType}
     */
    public CountingInputStream(@NonNull final InputStream inputStream, @NonNull final CounterType counterType) {
        this.inputStream = requireNonNull(inputStream);
        this.countingResult =
                counterType == CounterType.THREAD_SAFE ? new ThreadSafeByteCounter() : new FastByteCounter();
    }

    /**
     * Get the counting result. This method can be called at any time to get the current count of bytes read from the stream.
     *
     * @return the counting result
     */
    @NonNull
    public ByteCounter byteCounter() {
        return countingResult;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        final int aByte = inputStream.read();
        if (aByte != -1) {
            countingResult.addToCount(1L);
        }
        return aByte;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(@NonNull final byte[] bytes, final int offset, final int length) throws IOException {

        final int count = inputStream.read(bytes, offset, length);
        if (count != -1) {
            countingResult.addToCount(count);
        }
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public byte[] readNBytes(final int length) throws IOException {
        final byte[] bytes = inputStream.readNBytes(length);
        countingResult.addToCount(bytes.length);
        return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readNBytes(@NonNull final byte[] bytes, final int offset, final int length) throws IOException {
        final int count = inputStream.readNBytes(bytes, offset, length);
        countingResult.addToCount(count);
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(final long n) throws IOException {
        return inputStream.skip(n);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void skipNBytes(final long n) throws IOException {
        inputStream.skipNBytes(n);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int available() throws IOException {
        return inputStream.available();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        inputStream.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void mark(final int readLimit) {
        inputStream.mark(readLimit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean markSupported() {
        return inputStream.markSupported();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() throws IOException {
        inputStream.reset();
    }
}
