// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import org.hiero.consensus.io.counting.internal.FastByteCounter;
import org.hiero.consensus.io.counting.internal.ModifiableByteCounter;
import org.hiero.consensus.io.counting.internal.ThreadSafeByteCounter;

/**
 * An {@link OutputStream} that counts the number of bytes written to it. The count can be retrieved
 * at any time using the {@link #byteCounter()} method.
 */
public class CountingOutputStream extends OutputStream {

    private final OutputStream outputStream;
    private final ModifiableByteCounter countingResult;

    /**
     * Constructs a {@code CountingOutputStream}
     *
     * @param outputStream the base {@link OutputStream}
     * @param counterType the {@link CounterType}
     */
    public CountingOutputStream(@NonNull final OutputStream outputStream, @NonNull final CounterType counterType) {
        this.outputStream = requireNonNull(outputStream);
        this.countingResult =
                counterType == CounterType.THREAD_SAFE ? new ThreadSafeByteCounter() : new FastByteCounter();
    }

    /**
     * Get the counting result. This method can be called at any time to get the current count of bytes written to the stream.
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
    public void write(final int b) throws IOException {
        outputStream.write(b);
        countingResult.addToCount(1L);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(@NonNull final byte[] bytes, final int offset, final int length) throws IOException {
        outputStream.write(bytes, offset, length);
        countingResult.addToCount(length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() throws IOException {
        outputStream.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        outputStream.close();
    }
}
