// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Drainable;
import io.grpc.KnownLength;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An {@link InputStream} that implements {@link KnownLength} which allows the gRPC server to do
 * some smarter things when returning responses to clients. This stream is backed by a {@link
 * BufferedData}, with optimal implementations for the InputStream methods.
 */
final class KnownLengthStream extends InputStream implements KnownLength, Drainable {
    private final BufferedData buf;

    public KnownLengthStream(final BufferedData buf) {
        this.buf = requireNonNull(buf);
    }

    @Override
    public int read() {
        if (available() == 0) {
            return -1;
        }

        return this.buf.readUnsignedByte();
    }

    @Override
    public int read(@NonNull final byte[] b) {
        final int remaining = available();
        if (remaining == 0) {
            return -1;
        }

        final int numBytesToRead = Math.min(remaining, b.length);
        buf.readBytes(b, 0, numBytesToRead);
        return numBytesToRead;
    }

    @Override
    public int read(@NonNull final byte[] b, final int off, final int len) {
        final int remaining = available();
        if (remaining == 0) {
            return -1;
        }

        final int numBytesToRead = Math.min(remaining, len);
        buf.readBytes(b, off, numBytesToRead);
        return numBytesToRead;
    }

    @Override
    public long skip(final long n) {
        if (n <= 0) {
            return 0;
        }

        final int remaining = available();
        if (remaining == 0) {
            return 0;
        }

        final int numBytesToSkip = Math.min(remaining, (int) n);
        buf.skip(numBytesToSkip);
        return numBytesToSkip;
    }

    @Override
    public int available() {
        return (int) buf.remaining();
    }

    @Override
    public int drainTo(OutputStream outputStream) {
        final int remaining = available();
        if (remaining > 0) {
            buf.writeTo(outputStream);
        }
        return remaining;
    }
}
