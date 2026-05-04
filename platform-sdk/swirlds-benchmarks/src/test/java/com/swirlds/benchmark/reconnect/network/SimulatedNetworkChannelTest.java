// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SimulatedNetworkChannelTest {

    @Test
    void loopbackProfileIgnoresTimingAndBackpressure() {
        final NetworkSimulationConfig config =
                NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 500, 1_000, 131_072);

        assertEquals(NetworkProfile.LOOPBACK, config.profile());
        assertEquals(0, config.latencyNanos());
        assertEquals(Long.MAX_VALUE, config.bandwidthBytesPerSecond());
        assertEquals(Integer.MAX_VALUE, config.inflightBytesLimit());
    }

    @Test
    void realisticProfileConvertsUnits() {
        final NetworkSimulationConfig config =
                NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000, 131_072);

        assertEquals(NetworkProfile.REALISTIC, config.profile());
        assertEquals(500_000, config.latencyNanos());
        assertEquals(125_000_000L, config.bandwidthBytesPerSecond());
        assertEquals(131_072, config.inflightBytesLimit());
    }

    @Test
    void realisticProfileRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, -1, 1_000, 131_072));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 0, 131_072));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000, 0));
    }

    @Test
    void statsExposeCounterSnapshot() {
        final SimulatedNetworkStats stats = new SimulatedNetworkStats(10, 7, 4);

        assertEquals(10, stats.bytesWritten());
        assertEquals(7, stats.bytesRead());
        assertEquals(4, stats.maxInflightBytes());
        assertTrue(stats.toString().contains("bytesWritten=10"));
    }

    @Test
    void loopbackPreservesDataStreamFraming() throws Exception {
        final SimulatedNetworkChannel channel =
                new SimulatedNetworkChannel(NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 0, 1, 1));

        try (DataOutputStream out = new DataOutputStream(channel.outputStream());
                DataInputStream in = new DataInputStream(channel.inputStream())) {
            out.writeInt(4);
            out.write(new byte[] {1, 2, 3, 4});
            out.flush();

            final int length = in.readInt();
            final byte[] data = new byte[length];
            in.readFully(data);

            assertEquals(4, length);
            assertArrayEquals(new byte[] {1, 2, 3, 4}, data);
            assertEquals(8, channel.snapshotStats().bytesWritten());
            assertEquals(8, channel.snapshotStats().bytesRead());
        }
    }

    @Test
    void closeDrainsQueuedBytesThenReturnsEof() throws Exception {
        final SimulatedNetworkChannel channel =
                new SimulatedNetworkChannel(NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 0, 1, 1));

        final OutputStream out = channel.outputStream();
        final InputStream in = channel.inputStream();
        out.write(new byte[] {9, 8, 7});
        out.close();

        assertEquals(9, in.read());
        assertEquals(8, in.read());
        assertEquals(7, in.read());
        assertEquals(-1, in.read());
    }

    @Test
    void disconnectWakesBlockedReader() throws Exception {
        final SimulatedNetworkChannel channel = new SimulatedNetworkChannel(
                NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 1_000_000, 1, 1024));

        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread reader = new Thread(() -> {
            try {
                channel.inputStream().read();
            } catch (final Throwable t) {
                thrown.set(t);
            }
        });
        reader.start();

        awaitThreadState(reader, Thread.State.WAITING, Thread.State.TIMED_WAITING);
        channel.disconnect();
        reader.join(5_000);

        assertFalse(reader.isAlive());
        assertTrue(thrown.get() instanceof IOException);
    }

    private static void awaitThreadState(final Thread thread, final Thread.State... states) throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (final Thread.State state : states) {
                if (thread.getState() == state) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Thread did not reach expected state, actual=" + thread.getState());
    }
}
