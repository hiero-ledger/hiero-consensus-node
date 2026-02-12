// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.lag;

import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import java.util.function.Supplier;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * This variant of the async output stream introduces an extra delay for every single
 * message, which emulates I/O-related performance issues (slow disk when the message
 * was read from disk originally, and then slow network I/O).
 */
public class BenchmarkSlowAsyncOutputStream extends AsyncOutputStream {

    private final LongFuzzer delayStorageMicrosecondsFuzzer;
    private final LongFuzzer delayNetworkMicrosecondsFuzzer;

    public BenchmarkSlowAsyncOutputStream(
            final SerializableDataOutputStream out,
            final StandardWorkGroup workGroup,
            final Supplier<Boolean> alive,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent,
            final ReconnectConfig reconnectConfig) {
        super(out, workGroup, alive, reconnectConfig);

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
     */
    @Override
    public void sendAsync(final SelfSerializable message) throws InterruptedException {
        sleepMicros(delayStorageMicrosecondsFuzzer.next());
        super.sendAsync(message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void serializeMessage(final SelfSerializable message, final SerializableDataOutputStream out)
            throws IOException {
        sleepMicros(delayNetworkMicrosecondsFuzzer.next());
        super.serializeMessage(message, out);
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
