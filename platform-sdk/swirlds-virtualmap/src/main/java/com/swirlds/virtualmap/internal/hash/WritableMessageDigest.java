// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.RandomAccessData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.UncheckedIOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * A WritableSequentialData implementation that writes all the data into a given MessageDigest instance.
 */
public class WritableMessageDigest implements WritableSequentialData {
    private final MessageDigest digest;

    /**
     * A fake position that we keep increasing as we add data. We have to maintain it because there's code
     * in ProtoWriterTools that checks the position before and after writing data, and errors out if the position
     * hasn't changed as expected.
     */
    private long position = 0;

    /**
     * Constructor.
     * @param digest a MessageDigest object to write data to
     */
    public WritableMessageDigest(@NonNull final MessageDigest digest) {
        this.digest = Objects.requireNonNull(digest);
    }

    public void reset() {
        this.position = 0;
        digest.reset();
    }

    @Override
    public void writeByte(byte b) throws BufferOverflowException, UncheckedIOException {
        digest.update(b);
        position += 1;
    }

    @Override
    public void writeBytes(@NonNull byte[] src) throws BufferOverflowException, UncheckedIOException {
        digest.update(src);
        position += src.length;
    }

    @Override
    public void writeBytes(@NonNull byte[] src, int offset, int length)
            throws BufferOverflowException, UncheckedIOException {
        digest.update(src, offset, length);
        position += length;
    }

    @Override
    public void writeBytes(@NonNull ByteBuffer src) throws BufferOverflowException, UncheckedIOException {
        position += src.remaining();
        digest.update(src);
    }

    @Override
    public void writeBytes(@NonNull BufferedData src) throws BufferOverflowException, UncheckedIOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeBytes(@NonNull RandomAccessData src) throws BufferOverflowException, UncheckedIOException {
        throw new UnsupportedOperationException();
    }

    /**
     * This WritableSequentialData implementation feeds an underlying MessageDigest object, so there's not a concept
     * of a capacity here, and this method always returns Long.MAX_VALUE.
     * @return Long.MAX_VALUE
     */
    @Override
    public long capacity() {
        return Long.MAX_VALUE;
    }

    @Override
    public long position() {
        return position;
    }

    /**
     * This WritableSequentialData implementation feeds an underlying MessageDigest object, so there's not a concept
     * of a limit here, and this method always returns Long.MAX_VALUE.
     * @return Long.MAX_VALUE
     */
    @Override
    public long limit() {
        return Long.MAX_VALUE;
    }

    /**
     * This WritableSequentialData implementation feeds an underlying MessageDigest object, so there's not a concept
     * of a limit here, and this method is a no-op.
     */
    @Override
    public void limit(long limit) {}

    @Override
    public void skip(long count) throws UncheckedIOException {
        position += count;
    }
}