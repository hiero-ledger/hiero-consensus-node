// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes bytes immediately before they are handed to a raw socket output stream.
 *
 * <p>Each caller write is split into the controller's bounded ranges. A range is reserved before the matching raw
 * write so that a receiver which must drain the socket to unblock that write already has the scheduling metadata it
 * needs. The private lifecycle lock keeps reservation order identical to raw socket byte order without holding the
 * controller's lock across socket I/O.
 *
 * <p>This stream never delays a write to model latency or bandwidth and never copies caller payload. Capacity and
 * write blocking therefore remain properties of the real socket. Closing deliberately bypasses the lifecycle lock so
 * that it can wake a writer blocked in the socket.
 */
final class ObservedSocketOutputStream extends OutputStream {

    private final OutputStream out;
    private final SocketVisibilityController controller;
    private final SocketVisibilityController.AbortHandler abortHandler;
    private final Object writeLifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    ObservedSocketOutputStream(
            final OutputStream out,
            final SocketVisibilityController controller,
            final SocketVisibilityController.AbortHandler abortHandler) {
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.abortHandler = Objects.requireNonNull(abortHandler, "abortHandler must not be null");
    }

    /** Reserves and delegates one byte without allocating an intermediate array. */
    @Override
    public void write(final int value) throws IOException {
        try {
            synchronized (writeLifecycleLock) {
                final SocketVisibilityController.Reservation reservation = controller.reserveRange(1);
                final long startedNanos = controller.nanoTime();
                boolean succeeded = false;
                try {
                    out.write(value);
                    succeeded = true;
                } finally {
                    controller.recordRawWrite(
                            reservation, elapsedSince(startedNanos, controller.nanoTime()), succeeded);
                }
            }
        } catch (final IOException failure) {
            abortAfterUnlock(failure);
            throw failure;
        }
    }

    /**
     * Splits a caller range into bounded observed ranges, reserving each range immediately before its matching raw
     * write. The caller's array is delegated directly; no payload shadow buffer is created.
     */
    @Override
    public void write(final byte[] bytes, final int offset, final int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return;
        }

        try {
            synchronized (writeLifecycleLock) {
                int currentOffset = offset;
                int remaining = length;
                while (remaining > 0) {
                    final SocketVisibilityController.Reservation reservation = controller.reserveRange(remaining);
                    final int rangeBytes = reservation.byteCount();
                    final long startedNanos = controller.nanoTime();
                    boolean succeeded = false;
                    try {
                        out.write(bytes, currentOffset, rangeBytes);
                        succeeded = true;
                    } finally {
                        controller.recordRawWrite(
                                reservation, elapsedSince(startedNanos, controller.nanoTime()), succeeded);
                    }
                    currentOffset += rangeBytes;
                    remaining -= rangeBytes;
                }
            }
        } catch (final IOException failure) {
            abortAfterUnlock(failure);
            throw failure;
        }
    }

    /** Serializes flush with writes so it cannot overtake a partially delegated caller write. */
    @Override
    public void flush() throws IOException {
        try {
            synchronized (writeLifecycleLock) {
                out.flush();
            }
        } catch (final IOException failure) {
            abortAfterUnlock(failure);
            throw failure;
        }
    }

    /**
     * Signals controller cleanup before closing the raw stream. This method intentionally does not acquire
     * {@link #writeLifecycleLock}; closing the socket must be able to wake a concurrently blocked raw write.
     */
    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        controller.beginCleanup();
        try {
            out.close();
        } catch (final IOException failure) {
            abortAfterUnlock(failure);
            throw failure;
        }
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
