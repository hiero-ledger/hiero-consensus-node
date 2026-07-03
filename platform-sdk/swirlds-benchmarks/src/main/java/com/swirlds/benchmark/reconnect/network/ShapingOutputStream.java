// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

final class ShapingOutputStream extends FilterOutputStream {

    private static final int MAX_CHUNK_BYTES = 8192;

    private final long latencyNanos;
    private final long bandwidthBytesPerSecond;

    ShapingOutputStream(final OutputStream out, final long latencyNanos, final long bandwidthBytesPerSecond) {
        super(Objects.requireNonNull(out, "out must not be null"));
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be non-negative");
        }
        if (bandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthBytesPerSecond must be positive");
        }
        this.latencyNanos = latencyNanos;
        this.bandwidthBytesPerSecond = bandwidthBytesPerSecond;
    }

    @Override
    public void write(final int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return;
        }

        park(latencyNanos);

        int offset = off;
        int remaining = len;
        while (remaining > 0) {
            final int chunkBytes = Math.min(remaining, MAX_CHUNK_BYTES);
            out.write(b, offset, chunkBytes);
            park(transmitDurationNanos(chunkBytes));
            offset += chunkBytes;
            remaining -= chunkBytes;
        }
    }

    private long transmitDurationNanos(final int byteCount) {
        if (bandwidthBytesPerSecond == Long.MAX_VALUE) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(byteCount * 1_000_000_000.0 / bandwidthBytesPerSecond));
    }

    private static void park(final long nanos) throws IOException {
        if (nanos <= 0) {
            return;
        }
        LockSupport.parkNanos(nanos);
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted while shaping socket output");
        }
    }
}
