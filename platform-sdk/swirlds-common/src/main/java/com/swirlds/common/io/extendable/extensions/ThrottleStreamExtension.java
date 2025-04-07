// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.CompareTo.isGreaterThanOrEqualTo;

import com.swirlds.common.io.extendable.InputStreamExtension;
import com.swirlds.common.io.extendable.OutputStreamExtension;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A stream extension that limits the rate at which bytes may pass through the stream.
 */
public class ThrottleStreamExtension implements InputStreamExtension, OutputStreamExtension {

    private static final Duration DEFAULT_INCREMENT = Duration.ofMillis(100);

    private long bytesInCurrentIncrement;
    private final Duration timeIncrement;
    private Instant currentIncrementStart;
    private final long bytesPerIncrement;

    private InputStream inputStream;
    private OutputStream outputStream;

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final InputStream baseStream) {
        inputStream = baseStream;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final OutputStream baseStream) {
        outputStream = baseStream;
    }

    /**
     * Create a thread safe throttle extension.
     *
     * @param bytesPerSecond the maximum allowable throughput
     */
    public ThrottleStreamExtension(final long bytesPerSecond) {
        this(bytesPerSecond, DEFAULT_INCREMENT);
    }

    /**
     * Create a throttle extension.
     *
     * @param bytesPerSecond the maximum allowable throughput
     * @param timeIncrement  the time increment. Smaller time increments result in smoother throttling at the cost of
     *                       additional overhead.
     */
    public ThrottleStreamExtension(final long bytesPerSecond, final Duration timeIncrement) {
        this.timeIncrement = timeIncrement;
        this.bytesPerIncrement = (long) (bytesPerSecond * (timeIncrement.toNanos() * NANOSECONDS_TO_SECONDS));
        this.currentIncrementStart = Instant.now();
        this.bytesInCurrentIncrement = bytesPerIncrement;
    }

    /**
     * Check the current capacity of the stream, and return the number of bytes that are permitted to pass at this
     * moment. If the current capacity is 0 then this method will block until more capacity becomes available.
     *
     * @param requestedLength the requested number of bytes to pass
     * @return the allowed number of bytes to pass
     */
    public int getAvailableCapacity(final int requestedLength) {
        if (bytesInCurrentIncrement >= requestedLength) {
            return requestedLength;
        } else if (bytesInCurrentIncrement == 0) {
            final Instant now = Instant.now();
            final Duration elapsed = Duration.between(currentIncrementStart, now);
            final Duration remaining = timeIncrement.minus(elapsed);

            if (isGreaterThanOrEqualTo(remaining, Duration.ZERO)) {
                try {
                    MILLISECONDS.sleep(remaining.toMillis());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                currentIncrementStart = now.plus(remaining.toMillis(), ChronoUnit.MILLIS);
            } else {
                currentIncrementStart = now;
            }

            bytesInCurrentIncrement = bytesPerIncrement;

            if (bytesInCurrentIncrement >= requestedLength) {
                return requestedLength;
            } else {
                return (int) bytesInCurrentIncrement;
            }

        } else {
            return (int) bytesInCurrentIncrement;
        }
    }

    /**
     * Mark that bytes have passed through the stream in the current increment.
     */
    public void decrementCapacity(final int length) {
        if (bytesInCurrentIncrement < length) {
            throw new IllegalStateException("insufficient bytes in current increment");
        }
        bytesInCurrentIncrement -= length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        while (true) {
            if (getAvailableCapacity(1) != 0) {
                break;
            }
        }

        final int aByte = inputStream.read();
        decrementCapacity(1);

        return aByte;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(final byte[] bytes, final int offset, final int length) throws IOException {
        final int allowedBytes = getAvailableCapacity(length);

        final int read = inputStream.read(bytes, offset, allowedBytes);

        if (read == -1) {
            return -1;
        }

        decrementCapacity(read);
        return read;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] readNBytes(final int length) throws IOException {
        final byte[] bytes = new byte[length];
        final int bytesRead = readNBytes(bytes, 0, length);

        if (bytesRead < length) {
            final byte[] shortBytes = new byte[bytesRead];
            System.arraycopy(bytes, 0, shortBytes, 0, bytesRead);
            return shortBytes;
        }

        return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readNBytes(final byte[] bytes, final int offset, final int length) throws IOException {

        int bytesRead = 0;

        while (bytesRead < length) {
            final int remainingLength = length - bytesRead;
            final int currentOffset = offset + bytesRead;

            final int allowedBytes = getAvailableCapacity(remainingLength);

            final int read = inputStream.readNBytes(bytes, currentOffset, allowedBytes);

            decrementCapacity(read);
            bytesRead += read;

            if (read < allowedBytes) {
                break;
            }
        }

        return bytesRead;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final int b) throws IOException {
        while (true) {
            if (getAvailableCapacity(1) != 0) {
                break;
            }
        }

        outputStream.write(b);
        decrementCapacity(1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final byte[] bytes, final int offset, final int length) throws IOException {

        int bytesWritten = 0;

        while (bytesWritten < length) {
            final int remainingLength = length - bytesWritten;
            final int currentOffset = offset + bytesWritten;

            final int allowedBytes = getAvailableCapacity(remainingLength);

            outputStream.write(bytes, currentOffset, allowedBytes);
            decrementCapacity(allowedBytes);

            bytesWritten += allowedBytes;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        // No cleanup required
    }
}
