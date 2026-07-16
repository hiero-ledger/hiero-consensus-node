// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Read-side pacer that makes the real kernel socket buffers create backpressure for
 * {@link NetworkProfile#REALISTIC} socket runs.
 *
 * <p>The pacer is the bottom-most read wrapper, sitting directly on the raw socket {@link InputStream}: it is the only
 * component that ever reads the socket, so bytes it withholds physically stay in the kernel receive buffer. Releasing
 * at most one window {@code W} per RTT lets the receive buffer fill between releases, which closes the TCP window and
 * blocks the remote sender's {@code write()} on its full send buffer — real, kernel-driven backpressure whose severity
 * is set by the real (SocketFactory-configured, OS-clamped, autotuned) buffer sizes. Steady-state throughput is
 * {@code min(bandwidth, W / RTT)}.
 *
 * <p>{@code W} is re-read from the live sockets each time a window opens (see {@link WindowSupplier}), so kernel
 * receive-buffer autotuning widens the window mid-run. An {@code rttNanos} of zero disables the window mechanism
 * entirely (no supplier calls); the bandwidth cursor may still pace.
 *
 * <p><b>Latency semantics</b> (recorded decision 6 of the read-pacing design): latency is modeled as a throughput
 * window only. Per-message/first-byte one-way delay is NOT modeled — after any idle gap of at least one RTT, the first
 * window's worth of bytes flows with ~zero added delay, and latency emerges as throughput limiting under sustained
 * flow. Results are therefore not directly comparable with latency models that impose a first-byte delay at the same
 * latency parameter.
 *
 * <p><b>Read coalescing dependency</b> (recorded decision 2): the production sync-input factory puts either a
 * {@code BufferedInputStream} or an {@code InflaterInputStream} above its counting stream. Both JDK wrappers refill
 * through array reads, and the production counting stream preserves read arity, so the inherited no-arg
 * {@link #read()} is never invoked here. Workload-dependent part: for the uncompressed path, chunk size is only ~the
 * buffer size while messages stay smaller than it; {@code readFully} requests of at least the buffer size bypass
 * straight through to this pacer, which the per-read window clamp bounds. If the production wrappers are changed to
 * issue single-byte reads, this pacer must also gate the single-byte {@code read()}.
 *
 * <p><b>Teardown</b> (recorded decision 3): before this pacer existed, closing the socket alone woke a blocked reader;
 * a reader parked here is in a Java sleep that a socket close does not interrupt. The park is bounded by
 * {@code max(RTT, one chunk's transmit time at the configured bandwidth)}, after which the next read of the closed
 * socket fails and the reader exits cleanly — aborts are delayed by at most that bound, never hung. Thread interrupts
 * (used by the reconnect work group on shutdown) are honored after every park.
 *
 * <p>Driven by a single reader thread (the reconnect's async input reader); the stats accessors may be called from
 * other threads.
 *
 * <p>The inherited {@code skip()} and {@code available()} are NOT gated: {@code skip} would consume kernel-buffered
 * bytes without charging the window, and {@code available} reports bytes the pacer has not released. No consumer in
 * the reconnect stack calls either; if one ever does, both must be routed through the paced read path.
 */
final class PacingInputStream extends FilterInputStream {

    /**
     * Reads the current effective window for one direction: remote sender send-buffer plus local receiver
     * receive-buffer, live from the sockets. May throw once the sockets are closed mid-teardown; the pacer propagates
     * that from {@link #read(byte[], int, int)} — the same clean-abort path as reading a closed socket.
     */
    @FunctionalInterface
    interface WindowSupplier {
        int windowBytes() throws IOException;
    }

    /** Live pacing counters for the end-of-run diagnostics log. */
    record PacingStats(long windowsOpened, long lastWindowBytes, long totalParkedNanos) {}

    /** Round-trip time used as the window period; {@code 0} disables the window mechanism. */
    private final long rttNanos;

    /** Bandwidth cap in bytes per second; {@link Long#MAX_VALUE} disables the bandwidth cursor. */
    private final long bandwidthBytesPerSecond;

    /** Live per-direction effective-window readback, invoked once per window open. */
    private final WindowSupplier windowSupplier;

    /** End of the current RTT window; initialized to construction time so the first read opens a fresh window. */
    private long windowClosesAtNanos;

    /** Effective window {@code W} of the current RTT window. */
    private long windowBytes;

    /** Bytes handed to the consumer during the current window. */
    private long releasedThisWindow;

    /** Earliest time the next read may pull bytes, per the bandwidth cap (release-then-wait). */
    private long nextReadAvailableAtNanos;

    /** Latched once the underlying stream reports end-of-stream. */
    private boolean eofReached;

    // Diagnostics counters; volatile so an end-of-run logger thread sees current values.
    private volatile long windowsOpened;
    private volatile long lastWindowBytes;
    private volatile long totalParkedNanos;

    PacingInputStream(
            final InputStream in,
            final long rttNanos,
            final long bandwidthBytesPerSecond,
            final WindowSupplier windowSupplier) {
        super(Objects.requireNonNull(in, "in must not be null"));
        if (rttNanos < 0) {
            throw new IllegalArgumentException("rttNanos must be non-negative");
        }
        if (bandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthBytesPerSecond must be positive");
        }
        this.rttNanos = rttNanos;
        this.bandwidthBytesPerSecond = bandwidthBytesPerSecond;
        this.windowSupplier = Objects.requireNonNull(windowSupplier, "windowSupplier must not be null");
        // System.nanoTime() has an arbitrary (possibly negative) origin, so time sentinels must be seeded from the
        // live clock and only compared as differences; a 0 sentinel would mis-order against a negative-origin clock.
        final long now = System.nanoTime();
        this.windowClosesAtNanos = now;
        this.nextReadAvailableAtNanos = now;
    }

    /**
     * Reads at most the remaining window budget from the underlying socket, parking (outside the lock) until the
     * window or the bandwidth cursor allows the next release.
     *
     * <p>Never returns {@code 0} for a positive {@code len}: the InputStream contract that
     * {@code DataInputStream.readFully} relies on requires blocking until at least one byte or EOF.
     */
    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }

        while (true) {
            if (eofReached) {
                return -1;
            }

            final int toRead;
            final boolean mustPark;
            final long parkUntilNanos;
            synchronized (this) {
                final long now = System.nanoTime();
                if (rttNanos > 0 && now - windowClosesAtNanos >= 0) {
                    // A new RTT window opens: re-read the live effective window so autotuning is captured.
                    windowBytes = Math.max(1, windowSupplier.windowBytes());
                    releasedThisWindow = 0;
                    windowClosesAtNanos = now + rttNanos;
                    windowsOpened++;
                    lastWindowBytes = windowBytes;
                }
                final long budget = rttNanos > 0 ? windowBytes - releasedThisWindow : Long.MAX_VALUE;

                // Single collapsed park to the max-eligible time (window reopen and/or bandwidth cursor),
                // limiting parkNanos overshoot to one park per stall. Difference comparisons only: nanoTime's
                // origin is arbitrary.
                long eligibleAtNanos = nextReadAvailableAtNanos;
                if (budget <= 0 && windowClosesAtNanos - eligibleAtNanos > 0) {
                    eligibleAtNanos = windowClosesAtNanos;
                }
                if (eligibleAtNanos - now > 0) {
                    mustPark = true;
                    parkUntilNanos = eligibleAtNanos;
                    toRead = 0;
                } else {
                    mustPark = false;
                    parkUntilNanos = 0;
                    // Clamp to the remaining budget so a large readFully bypass cannot overshoot the window.
                    toRead = (int) Math.max(1, Math.min(len, budget));
                }
            }

            if (mustPark) {
                parkAndCheckInterrupt(parkUntilNanos);
                continue;
            }

            // The only place the socket is read; this is the gate that withholds bytes in the kernel buffer.
            final int n = in.read(b, off, toRead);
            if (n < 0) {
                eofReached = true;
                return -1;
            }
            if (n == 0) {
                // Defensive: a blocking socket read never returns 0 for len > 0; retry rather than propagate.
                continue;
            }

            synchronized (this) {
                releasedThisWindow += n;
                final long now = System.nanoTime();
                final long cursorBase = now - nextReadAvailableAtNanos > 0 ? now : nextReadAvailableAtNanos;
                nextReadAvailableAtNanos = cursorBase + transmitDurationNanos(n);
            }
            return n;
        }
    }

    /** Live pacing counters; safe to call from a thread other than the reader. */
    PacingStats stats() {
        return new PacingStats(windowsOpened, lastWindowBytes, totalParkedNanos);
    }

    /** Computes how long the released bytes occupy the modeled link (formerly {@code ShapingOutputStream}). */
    private long transmitDurationNanos(final int byteCount) {
        if (bandwidthBytesPerSecond == Long.MAX_VALUE) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(byteCount * 1_000_000_000.0 / bandwidthBytesPerSecond));
    }

    private void parkAndCheckInterrupt(final long untilNanos) throws IOException {
        final long start = System.nanoTime();
        final long delta = untilNanos - start;
        if (delta > 0) {
            LockSupport.parkNanos(delta);
            totalParkedNanos += System.nanoTime() - start;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted while pacing socket reads");
        }
    }
}
