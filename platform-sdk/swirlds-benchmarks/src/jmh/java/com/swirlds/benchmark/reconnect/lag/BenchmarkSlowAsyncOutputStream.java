// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.lag;

import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.jspecify.annotations.NonNull;

/**
 * This variant of the async output stream introduces an extra delay for every single
 * message, which emulates I/O-related performance issues (slow disk when the message
 * was read from disk originally, and then slow network I/O).
 *
 * <p>Storage delay is applied on the caller thread in {@link #sendAsync(byte[])},
 * simulating the time to read data from disk. Network delay is applied on the writer
 * thread in {@link #writeMessage(byte[])}, simulating the time to push data over the
 * network.
 */
public class BenchmarkSlowAsyncOutputStream extends AsyncOutputStream {

    private final LongFuzzer delayStorageMicrosecondsFuzzer;
    private final LongFuzzer delayNetworkMicrosecondsFuzzer;

    /**
     * Create a new benchmark slow async output stream.
     *
     * @param out the underlying output stream
     * @param workGroup the work group managing this stream's thread
     * @param randomSeed seed for the delay fuzzers
     * @param delayStorageMicroseconds base storage delay in microseconds
     * @param delayStorageFuzzRangePercent fuzz range for storage delay as a percentage
     * @param delayNetworkMicroseconds base network delay in microseconds
     * @param delayNetworkFuzzRangePercent fuzz range for network delay as a percentage
     * @param reconnectConfig the reconnect configuration
     */
    public BenchmarkSlowAsyncOutputStream(
            @NonNull final DataOutputStream out,
            @NonNull final StandardWorkGroup workGroup,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent,
            @NonNull final ReconnectConfig reconnectConfig) {
        super(out, workGroup, reconnectConfig);

        // Note that we use randomSeed and -randomSeed for the two fuzzers
        // to ensure that they don't end up returning the exact same
        // (relatively, that is, in percentages) delay
        // for both the storage and network.
        delayStorageMicrosecondsFuzzer =
                new LongFuzzer(delayStorageMicroseconds, new Random(randomSeed), delayStorageFuzzRangePercent);
        delayNetworkMicrosecondsFuzzer =
                new LongFuzzer(delayNetworkMicroseconds, new Random(-randomSeed), delayNetworkFuzzRangePercent);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies a fuzzed storage delay on the caller thread before enqueueing the message,
     * simulating slow disk reads.
     */
    @Override
    public void sendAsync(@NonNull final byte[] messageBytes) throws InterruptedException {
        sleepMicros(delayStorageMicrosecondsFuzzer.next());
        super.sendAsync(messageBytes);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies a fuzzed network delay on the writer thread before writing the message,
     * simulating slow network I/O.
     */
    @Override
    protected void writeMessage(@NonNull final byte[] messageBytes) throws IOException {
        sleepMicros(delayNetworkMicrosecondsFuzzer.next());
        super.writeMessage(messageBytes);
    }

    /**
     * Sleep for a given number of microseconds.
     * @param micros time to sleep, in microseconds
     */
    private static void sleepMicros(final long micros) {
        try {
            Thread.sleep(Duration.ofNanos(micros * 1000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
