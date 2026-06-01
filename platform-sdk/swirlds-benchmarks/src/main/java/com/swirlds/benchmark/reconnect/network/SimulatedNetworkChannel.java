// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Models one direction of the ReconnectBench network link as a benchmark-only {@link OutputStream}/{@link InputStream}
 * pair.
 *
 * <p>ReconnectBench creates two channels: one for teacher-to-learner traffic and one for learner-to-teacher traffic.
 * The channel sits below {@code BufferedOutputStream}/{@code BufferedInputStream}, so Java stream buffering still
 * decides when bytes leave the local buffer. Once bytes reach this channel, the channel models what happens on the
 * wire:
 *
 * <pre>{@code
 * sender OutputStream -> queued ByteRange -> scheduled arrival -> receiver InputStream
 * }</pre>
 *
 * <p>The reconnect-specific goal is to control when request and response bytes become visible to the peer. Traversal
 * modes speculate based on responses that have arrived so far, so the benchmark needs byte-level delivery timing rather
 * than a single delay per reconnect message or per flush.
 *
 * <p>The model intentionally covers only the effects needed for ReconnectBench comparisons:
 *
 * <ul>
 *   <li>latency: delivery of a written range starts after the configured one-way latency;
 *   <li>bandwidth: each byte range occupies the simulated link until its transmit duration has elapsed;
 *   <li>backpressure: the sender blocks when accepted-but-unread bytes would exceed the in-flight cap.
 * </ul>
 *
 * <p>All mutable channel state is guarded by {@link #lock}. Closing the output side is a normal end-of-stream:
 * queued bytes drain first and then the reader sees EOF. Closing the input side rejects future writes.
 * {@link #disconnect()} is an abort path used by reconnect failure handling; it wakes blocked readers and writers with
 * an {@link IOException}.
 */
public class SimulatedNetworkChannel {

    /** Maximum byte range accepted into the simulated wire at once. Keeps large flushes progressively readable. */
    private static final int DEFAULT_RANGE_SIZE = 8192;

    /** Small read coalescing target so bandwidth-limited streams do not degrade to tiny reads per byte. */
    private static final int MIN_PROGRESSIVE_READ_BYTES = 64;

    /** Resolved latency, bandwidth, and in-flight byte cap for this direction. */
    private final NetworkSimulationConfig config;

    /** Guards all mutable channel state below. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signals readers and writers when data, capacity, close state, or disconnect state changes. */
    private final Condition stateChanged = lock.newCondition();

    /** FIFO of accepted byte ranges that have not yet been fully read by the receiver. */
    private final Queue<ByteRange> ranges = new ArrayDeque<>();

    /** Receiver side of this one-way channel. */
    private final InputStream inputStream = new ChannelInputStream();

    /** Sender side of this one-way channel. */
    private final OutputStream outputStream = new ChannelOutputStream();

    /** Total bytes accepted from the sender. */
    private long bytesWritten;

    /** Total bytes handed to the receiver. */
    private long bytesRead;

    /** Bytes accepted by the channel but not yet handed to the receiver. */
    private long inflightBytes;

    /** Largest observed value of {@link #inflightBytes}. */
    private long maxInflightBytes;

    /** Number of top-level write calls received from the sending stream. */
    private long writeCalls;

    /** Number of byte ranges scheduled on the simulated link after write splitting. */
    private long writeRanges;

    /** Number of read calls received from the receiving stream. */
    private long readCalls;

    /** Number of waits caused by the in-flight byte cap. */
    private long capacityWaitCount;

    /** Cumulative nanoseconds spent waiting for in-flight capacity. */
    private long capacityWaitNanos;

    /** Number of waits while the reader had no queued data to inspect. */
    private long emptyReadWaitCount;

    /** Cumulative nanoseconds spent waiting for any queued data to appear. */
    private long emptyReadWaitNanos;

    /** Number of waits while queued data existed but had not reached its simulated arrival time. */
    private long arrivalWaitCount;

    /** Cumulative nanoseconds spent waiting for scheduled byte arrival. */
    private long arrivalWaitNanos;

    /** End time of the last scheduled transmission; serializes bandwidth use for this direction. */
    private long nextTransmissionAvailableAtNanos;

    /** True when the output side has closed normally; queued bytes still drain before EOF. */
    private boolean closed;

    /** True when the input side has closed; future writes fail because there is no receiver. */
    private boolean inputClosed;

    /** True after an emergency abort; blocked readers and writers fail immediately. */
    private boolean disconnected;

    /**
     * Creates a one-way simulated network channel.
     *
     * @param config resolved simulation parameters for this direction
     */
    public SimulatedNetworkChannel(final NetworkSimulationConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Returns the receiver side of this channel.
     *
     * <p>Reads block until data is queued and sufficiently arrived according to the latency and bandwidth schedule.
     */
    public InputStream inputStream() {
        return inputStream;
    }

    /**
     * Returns the sender side of this channel.
     *
     * <p>Copies the provided bytes into the simulated wire, schedules their arrival, and may block when the in-flight
     * cap is full.
     */
    public OutputStream outputStream() {
        return outputStream;
    }

    /**
     * Captures the current byte, in-flight, and wait diagnostics for this channel.
     *
     * @return an immutable snapshot of channel counters
     */
    public SimulatedNetworkStats snapshotStats() {
        lock.lock();
        try {
            return new SimulatedNetworkStats(
                    bytesWritten,
                    bytesRead,
                    maxInflightBytes,
                    writeCalls,
                    writeRanges,
                    readCalls,
                    capacityWaitCount,
                    capacityWaitNanos,
                    emptyReadWaitCount,
                    emptyReadWaitNanos,
                    arrivalWaitCount,
                    arrivalWaitNanos);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Aborts this channel and wakes blocked readers and writers.
     *
     * <p>Unlike normal output close, disconnect does not preserve EOF semantics. It is the benchmark equivalent of a
     * broken reconnect connection.
     */
    public void disconnect() {
        lock.lock();
        try {
            disconnected = true;
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Throws if reconnect failure handling has aborted this channel. */
    private void ensureConnected() throws IOException {
        if (disconnected) {
            throw new IOException("Simulated network channel is disconnected");
        }
    }

    /** Sender-side stream. Accepts bytes into the simulated wire. */
    private final class ChannelOutputStream extends OutputStream {

        @Override
        public void write(final int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        /**
         * Copies bytes into one or more scheduled byte ranges.
         *
         * <p>Large writes can arrive from a lower-level buffer flush. Splitting them keeps early bytes readable while
         * the rest of the flushed payload is still "on the wire" and prevents writes larger than the in-flight cap from
         * deadlocking.
         */
        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            lock.lock();
            try {
                ensureConnected();
                ensureOpenForWrite();
                ensureReceiverOpen();
                writeCalls++;
            } finally {
                lock.unlock();
            }

            int offset = off;
            int remaining = len;
            while (remaining > 0) {
                final int accepted = Math.min(remaining, Math.min(DEFAULT_RANGE_SIZE, config.inflightBytesLimit()));
                writeRange(Arrays.copyOfRange(b, offset, offset + accepted));
                offset += accepted;
                remaining -= accepted;
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                closed = true;
                stateChanged.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Schedules a byte range for delivery.
         *
         * <p>The sender waits for capacity before the range is accepted. Once accepted, the range is counted as in
         * flight until the receiving stream reads it. Bandwidth is modeled by scheduling this range after the previous
         * range's transmission window.
         */
        private void writeRange(final byte[] bytes) throws IOException {
            lock.lock();
            try {
                ensureConnected();
                ensureOpenForWrite();
                ensureReceiverOpen();
                while (inflightBytes + bytes.length > config.inflightBytesLimit()) {
                    final long waitStart = System.nanoTime();
                    try {
                        stateChanged.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for simulated network capacity", e);
                    } finally {
                        capacityWaitCount++;
                        capacityWaitNanos += System.nanoTime() - waitStart;
                    }
                    ensureConnected();
                    ensureOpenForWrite();
                    ensureReceiverOpen();
                }

                // A range may start only after the previous range has finished using the simulated link.
                final long now = System.nanoTime();
                final long sendStart = Math.max(now, nextTransmissionAvailableAtNanos);
                final long sendEnd = sendStart + transmitDurationNanos(bytes.length);

                // Latency shifts delivery; bandwidth stretches it across arrivalStart..arrivalEnd.
                ranges.add(new ByteRange(bytes, sendStart + config.latencyNanos(), sendEnd + config.latencyNanos()));
                nextTransmissionAvailableAtNanos = sendEnd;
                writeRanges++;
                bytesWritten += bytes.length;
                inflightBytes += bytes.length;
                maxInflightBytes = Math.max(maxInflightBytes, inflightBytes);
                stateChanged.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Throws if the sender side was closed normally. */
        private void ensureOpenForWrite() throws IOException {
            if (closed) {
                throw new IOException("Simulated network channel output is closed");
            }
        }

        /** Throws if the receiver side has been closed and can no longer drain bytes. */
        private void ensureReceiverOpen() throws IOException {
            if (inputClosed) {
                throw new IOException("Simulated network channel input is closed");
            }
        }
    }

    /** Computes how long this byte range occupies the simulated link. */
    private long transmitDurationNanos(final int byteCount) {
        if (config.bandwidthBytesPerSecond() == Long.MAX_VALUE) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(byteCount * 1_000_000_000.0 / config.bandwidthBytesPerSecond()));
    }

    /** Receiver-side stream. Releases bytes only after their simulated arrival time. */
    private final class ChannelInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int count = read(one, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        /**
         * Reads arrived bytes from the head of the channel FIFO.
         *
         * <p>If no range is queued, the reader waits for the sender or EOF. If a range is queued but not yet visible,
         * the reader waits until enough bytes have arrived. Bytes stop counting as in flight only when this method
         * hands them to the receiver.
         */
        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }

            lock.lock();
            try {
                ensureOpenForRead();
                readCalls++;
                while (true) {
                    ensureConnected();
                    ensureOpenForRead();
                    final ByteRange range = ranges.peek();
                    if (range == null) {
                        if (closed) {
                            return -1;
                        }
                        awaitStateChange();
                        continue;
                    }

                    final long now = System.nanoTime();
                    final int available = range.availableBytes(now);
                    if (available == 0) {
                        awaitNanos(Math.max(1, range.nextReadableAtNanos(now) - now));
                        continue;
                    }

                    final int targetAvailable =
                            Math.min(len, Math.min(range.remainingBytes(), MIN_PROGRESSIVE_READ_BYTES));
                    if (available < targetAvailable) {
                        awaitNanos(Math.max(1, range.readableAtNanos(targetAvailable) - now));
                        continue;
                    }

                    final int toRead = Math.min(len, available);
                    range.readInto(b, off, toRead);
                    bytesRead += toRead;

                    // Capacity is freed when the receiver actually observes bytes, not when they merely arrive.
                    inflightBytes -= toRead;
                    if (range.isFullyRead()) {
                        ranges.remove();
                    }
                    stateChanged.signalAll();
                    return toRead;
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                inputClosed = true;
                stateChanged.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Throws if this receiver has been closed. */
        private void ensureOpenForRead() throws IOException {
            if (inputClosed) {
                throw new IOException("Simulated network channel input is closed");
            }
        }

        /** Waits for data, close, or disconnect when the FIFO is empty. */
        private void awaitStateChange() throws IOException {
            final long waitStart = System.nanoTime();
            try {
                stateChanged.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for simulated network data", e);
            } finally {
                emptyReadWaitCount++;
                emptyReadWaitNanos += System.nanoTime() - waitStart;
            }
        }

        /** Waits until the head range reaches the requested simulated arrival time. */
        private void awaitNanos(final long nanos) throws IOException {
            final long waitStart = System.nanoTime();
            try {
                stateChanged.awaitNanos(nanos);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for simulated network timing", e);
            } finally {
                arrivalWaitCount++;
                arrivalWaitNanos += System.nanoTime() - waitStart;
            }
        }
    }

    /**
     * A copied range of bytes accepted into the simulated wire.
     *
     * <p>{@code arrivalStartNanos} is when the first byte can be read. {@code arrivalEndNanos} is when the full range
     * can be read. Between those points, bytes become readable progressively according to elapsed transmit time.
     */
    private static final class ByteRange {
        /** Copied payload bytes. */
        private final byte[] bytes;

        /** Earliest time the first byte is visible to the receiver. */
        private final long arrivalStartNanos;

        /** Earliest time the full range is visible to the receiver. */
        private final long arrivalEndNanos;

        /** Next unread byte offset in {@link #bytes}. */
        private int readOffset;

        private ByteRange(final byte[] bytes, final long arrivalStartNanos, final long arrivalEndNanos) {
            this.bytes = bytes;
            this.arrivalStartNanos = arrivalStartNanos;
            this.arrivalEndNanos = arrivalEndNanos;
        }

        /**
         * Returns how many unread bytes may be consumed at {@code now}.
         *
         * <p>Before {@link #arrivalStartNanos}, no bytes are visible. After {@link #arrivalEndNanos}, all remaining
         * bytes are visible. Between those times, visibility advances linearly through the range.
         */
        private int availableBytes(final long now) {
            if (now < arrivalStartNanos) {
                return 0;
            }
            if (arrivalEndNanos <= arrivalStartNanos || now >= arrivalEndNanos) {
                return bytes.length - readOffset;
            }
            final double progress = (double) (now - arrivalStartNanos) / (arrivalEndNanos - arrivalStartNanos);
            final int arrived = Math.max(1, Math.min(bytes.length, (int) Math.floor(progress * bytes.length)));
            return Math.max(0, arrived - readOffset);
        }

        /** Returns the earliest time at least one more byte is readable. */
        private long nextReadableAtNanos(final long now) {
            if (readOffset == 0) {
                return arrivalStartNanos;
            }
            if (arrivalEndNanos <= arrivalStartNanos) {
                return now;
            }
            final double nextByteFraction = (double) (readOffset + 1) / bytes.length;
            return arrivalStartNanos + (long) Math.ceil(nextByteFraction * (arrivalEndNanos - arrivalStartNanos));
        }

        /** Returns the earliest time the requested number of additional bytes is readable. */
        private long readableAtNanos(final int additionalReadableBytes) {
            final int targetOffset = Math.min(bytes.length, readOffset + additionalReadableBytes);
            if (targetOffset <= 1 || arrivalEndNanos <= arrivalStartNanos) {
                return arrivalStartNanos;
            }
            final double targetFraction = (double) targetOffset / bytes.length;
            return arrivalStartNanos + (long) Math.ceil(targetFraction * (arrivalEndNanos - arrivalStartNanos));
        }

        /** Returns the unread bytes remaining in this range. */
        private int remainingBytes() {
            return bytes.length - readOffset;
        }

        /** Copies readable bytes to the receiver buffer and advances the read offset. */
        private void readInto(final byte[] destination, final int destinationOffset, final int count) {
            System.arraycopy(bytes, readOffset, destination, destinationOffset, count);
            readOffset += count;
        }

        /** Returns true after this range has been fully handed to the receiver. */
        private boolean isFullyRead() {
            return readOffset == bytes.length;
        }
    }
}
