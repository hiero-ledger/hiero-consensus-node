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

public class SimulatedNetworkChannel {

    private static final int DEFAULT_RANGE_SIZE = 8192;

    private final NetworkSimulationConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();
    private final Queue<ByteRange> ranges = new ArrayDeque<>();
    private final InputStream inputStream = new ChannelInputStream();
    private final OutputStream outputStream = new ChannelOutputStream();

    private long bytesWritten;
    private long bytesRead;
    private long inflightBytes;
    private long maxInflightBytes;
    private long writeCalls;
    private long writeRanges;
    private long readCalls;
    private long capacityWaitCount;
    private long capacityWaitNanos;
    private long emptyReadWaitCount;
    private long emptyReadWaitNanos;
    private long arrivalWaitCount;
    private long arrivalWaitNanos;
    private long nextTransmissionAvailableAtNanos;
    private boolean closed;
    private boolean disconnected;

    public SimulatedNetworkChannel(final NetworkSimulationConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public InputStream inputStream() {
        return inputStream;
    }

    public OutputStream outputStream() {
        return outputStream;
    }

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

    public void disconnect() {
        lock.lock();
        try {
            disconnected = true;
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void ensureConnected() throws IOException {
        if (disconnected) {
            throw new IOException("Simulated network channel is disconnected");
        }
    }

    private final class ChannelOutputStream extends OutputStream {

        @Override
        public void write(final int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            lock.lock();
            try {
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

        private void writeRange(final byte[] bytes) throws IOException {
            lock.lock();
            try {
                ensureConnected();
                ensureOpenForWrite();
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
                }

                final long now = System.nanoTime();
                final long sendStart = Math.max(now, nextTransmissionAvailableAtNanos);
                final long sendEnd = sendStart + transmitDurationNanos(bytes.length);
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

        private void ensureOpenForWrite() throws IOException {
            if (closed) {
                throw new IOException("Simulated network channel output is closed");
            }
        }
    }

    private long transmitDurationNanos(final int byteCount) {
        if (config.bandwidthBytesPerSecond() == Long.MAX_VALUE) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(byteCount * 1_000_000_000.0 / config.bandwidthBytesPerSecond()));
    }

    private final class ChannelInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int count = read(one, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }

            lock.lock();
            try {
                readCalls++;
                while (true) {
                    ensureConnected();
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

                    final int toRead = Math.min(len, available);
                    range.readInto(b, off, toRead);
                    bytesRead += toRead;
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

    private static final class ByteRange {
        private final byte[] bytes;
        private final long arrivalStartNanos;
        private final long arrivalEndNanos;
        private int readOffset;

        private ByteRange(final byte[] bytes, final long arrivalStartNanos, final long arrivalEndNanos) {
            this.bytes = bytes;
            this.arrivalStartNanos = arrivalStartNanos;
            this.arrivalEndNanos = arrivalEndNanos;
        }

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

        private void readInto(final byte[] destination, final int destinationOffset, final int count) {
            System.arraycopy(bytes, readOffset, destination, destinationOffset, count);
            readOffset += count;
        }

        private boolean isFullyRead() {
            return readOffset == bytes.length;
        }
    }
}
