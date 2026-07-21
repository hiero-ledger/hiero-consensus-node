// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receiver-side gate which exposes raw socket bytes only when their sender-relative schedule is eligible.
 *
 * <p>The controller stores range metadata, not payload. This stream waits for an eligible prefix before touching the
 * raw socket and clamps every consuming read to that prefix, so ineligible bytes stay in the kernel receive buffer.
 * A private lifecycle lock enforces the single-reader invariant and keeps allowance consumption ordered.
 *
 * <p>One logical timeout covers metadata and scheduling waits in the controller plus the subsequent raw socket read.
 * Before raw I/O, the remaining deadline is converted to the socket API's ceiling-millisecond timeout. Closing does
 * not acquire the reader lock, allowing it to wake a reader blocked in raw socket I/O.
 */
final class ScheduledSocketInputStream extends InputStream {

    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final int SKIP_BUFFER_BYTES = 8 * 1_024;

    @FunctionalInterface
    interface SocketTimeoutSetter {
        void setSoTimeout(int timeoutMillis) throws IOException;
    }

    private final InputStream in;
    private final SocketTimeoutSetter timeoutSetter;
    private final int configuredTimeoutMillis;
    private final SocketVisibilityController controller;
    private final SocketVisibilityController.AbortHandler abortHandler;
    private final Object readLifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final byte[] singleByte = new byte[1];
    private final byte[] skipBuffer = new byte[SKIP_BUFFER_BYTES];

    ScheduledSocketInputStream(
            final InputStream in,
            final Socket socket,
            final int configuredTimeoutMillis,
            final SocketVisibilityController controller,
            final SocketVisibilityController.AbortHandler abortHandler) {
        this(in, timeoutSetterFor(socket), configuredTimeoutMillis, controller, abortHandler);
    }

    /** Package-private seam for deterministic timeout and controlled-stream tests. */
    ScheduledSocketInputStream(
            final InputStream in,
            final SocketTimeoutSetter timeoutSetter,
            final int configuredTimeoutMillis,
            final SocketVisibilityController controller,
            final SocketVisibilityController.AbortHandler abortHandler) {
        this.in = Objects.requireNonNull(in, "in must not be null");
        this.timeoutSetter = Objects.requireNonNull(timeoutSetter, "timeoutSetter must not be null");
        if (configuredTimeoutMillis < 0) {
            throw new IllegalArgumentException("configuredTimeoutMillis must be non-negative");
        }
        this.configuredTimeoutMillis = configuredTimeoutMillis;
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.abortHandler = Objects.requireNonNull(abortHandler, "abortHandler must not be null");
    }

    /** Delegates to the gated array-read path so single-byte reads cannot bypass scheduling. */
    @Override
    public int read() throws IOException {
        final int count = read(singleByte, 0, 1);
        return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
    }

    /**
     * Waits for metadata and eligibility before issuing a raw read clamped to the returned allowance. Only bytes
     * actually returned by the raw socket are consumed from controller state.
     */
    @Override
    public int read(final byte[] bytes, final int offset, final int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }

        try {
            synchronized (readLifecycleLock) {
                return readWhileLocked(bytes, offset, length);
            }
        } catch (final IOException failure) {
            abortAfterUnlock(failure);
            throw failure;
        }
    }

    private int readWhileLocked(final byte[] bytes, final int offset, final int length) throws IOException {
        final boolean hasDeadline = configuredTimeoutMillis > 0;
        final long deadlineNanos =
                hasDeadline ? controller.nanoTime() + configuredTimeoutMillis * NANOS_PER_MILLISECOND : 0;

        while (true) {
            final SocketVisibilityController.ReadAllowance allowance =
                    controller.awaitReadable(length, deadlineNanos, hasDeadline);

            final boolean timeoutChanged = applyRemainingSocketTimeout(deadlineNanos, hasDeadline);
            final long rawReadStartedNanos = controller.nanoTime();
            final int count;
            try {
                count = in.read(bytes, offset, allowance.byteCount());
            } catch (final IOException failure) {
                controller.recordRawReadFailure(allowance, elapsedSince(rawReadStartedNanos, controller.nanoTime()));
                restoreAfterFailure(timeoutChanged, failure);
                throw failure;
            }

            final long rawReadNanos = elapsedSince(rawReadStartedNanos, controller.nanoTime());
            if (count < 0) {
                final IOException failure = new IOException(
                        "Unexpected socket EOF; successful reconnect termination uses an in-band frame");
                controller.recordRawReadFailure(allowance, rawReadNanos);
                restoreAfterFailure(timeoutChanged, failure);
                throw failure;
            }
            if (count == 0) {
                // A blocking socket must not return zero for a positive request. Preserve InputStream semantics for
                // defensive test delegates by retrying under the original logical deadline.
                restoreConfiguredSocketTimeoutIfChanged(timeoutChanged);
                continue;
            }

            // Consume the actual count, not the allowance: a socket read may return any positive eligible prefix.
            try {
                controller.consume(allowance, count, rawReadNanos);
            } catch (final IOException failure) {
                // The raw socket has already consumed these bytes. Preserve the controller failure as primary,
                // restore the socket option best-effort, and let the outer path abort the unusable connection.
                restoreAfterFailure(timeoutChanged, failure);
                throw failure;
            }
            restoreConfiguredSocketTimeoutIfChanged(timeoutChanged);
            return count;
        }
    }

    /** Consumes skipped bytes through the gated read path rather than bypassing the controller via the raw stream. */
    @Override
    public long skip(final long byteCount) throws IOException {
        if (byteCount <= 0) {
            return 0;
        }
        return read(skipBuffer, 0, (int) Math.min(byteCount, skipBuffer.length));
    }

    /** Reports only bytes which are both scheduled as eligible and already available from the raw socket. */
    @Override
    public int available() throws IOException {
        try {
            synchronized (readLifecycleLock) {
                final int rawAvailable = in.available();
                if (rawAvailable == 0) {
                    return 0;
                }
                return Math.min(rawAvailable, controller.eligibleBytesNow(rawAvailable));
            }
        } catch (final IOException failure) {
            abortAfterUnlock(failure);
            throw failure;
        }
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(final int readLimit) {
        // Socket payload cannot be replayed without violating controller sequence accounting.
    }

    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset is not supported by a scheduled socket stream");
    }

    /**
     * Signals controller cleanup before closing the raw stream. This method intentionally bypasses
     * {@link #readLifecycleLock}, allowing close to wake a raw socket read.
     */
    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        controller.beginCleanup();
        try {
            in.close();
        } catch (final IOException failure) {
            abortAfterUnlock(failure);
            throw failure;
        }
    }

    /** Returns whether the raw socket timeout was changed and therefore needs restoring. */
    private boolean applyRemainingSocketTimeout(final long deadlineNanos, final boolean hasDeadline)
            throws IOException {
        if (!hasDeadline) {
            return false;
        }

        final long remainingNanos = deadlineNanos - controller.nanoTime();
        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("Timed out before an eligible socket range could be read");
        }
        final int remainingMillis = ceilMillis(remainingNanos);
        if (remainingMillis == configuredTimeoutMillis) {
            return false;
        }
        timeoutSetter.setSoTimeout(remainingMillis);
        return true;
    }

    private void restoreConfiguredSocketTimeoutIfChanged(final boolean timeoutChanged) throws IOException {
        if (timeoutChanged) {
            timeoutSetter.setSoTimeout(configuredTimeoutMillis);
        }
    }

    private void restoreAfterFailure(final boolean timeoutChanged, final IOException primaryFailure) {
        if (!timeoutChanged) {
            return;
        }
        try {
            timeoutSetter.setSoTimeout(configuredTimeoutMillis);
        } catch (final IOException restoreFailure) {
            primaryFailure.addSuppressed(restoreFailure);
        }
    }

    private static int ceilMillis(final long durationNanos) {
        final long millis = 1 + (durationNanos - 1) / NANOS_PER_MILLISECOND;
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private static SocketTimeoutSetter timeoutSetterFor(final Socket socket) {
        return Objects.requireNonNull(socket, "socket must not be null")::setSoTimeout;
    }

    private static long elapsedSince(final long startNanos, final long endNanos) {
        return Math.max(0, endNanos - startNanos);
    }

    /** The handler may close this raw stream, so it must only be invoked after the lifecycle lock is released. */
    private void abortAfterUnlock(final IOException failure) {
        try {
            abortHandler.abort(failure);
        } catch (final RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }
}
