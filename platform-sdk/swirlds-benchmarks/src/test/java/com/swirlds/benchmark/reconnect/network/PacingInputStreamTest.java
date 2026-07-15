// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the read-side pacer. Tests use array reads only: the no-arg {@code read()} is deliberately not
 * gated (recorded decision 2 — the real stack's {@code BufferedInputStream} guarantees it is never invoked).
 */
class PacingInputStreamTest {

    private static final long ONE_HUNDRED_FIFTY_MILLIS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long ONE_HUNDRED_MILLIS = TimeUnit.MILLISECONDS.toNanos(100);

    private static int readFully(final PacingInputStream in, final byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            final int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) {
                break;
            }
            assertTrue(n > 0, "read must never return 0 for a positive length");
            total += n;
        }
        return total;
    }

    @Test
    void windowBudgetSpreadsTransferAcrossRtts() throws Exception {
        final byte[] data = new byte[50_000];
        final PacingInputStream pacer = new PacingInputStream(
                new ByteArrayInputStream(data), ONE_HUNDRED_FIFTY_MILLIS, Long.MAX_VALUE, () -> 10_000);

        final long start = System.nanoTime();
        assertEquals(data.length, readFully(pacer, new byte[data.length]));
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 5 windows of 10_000 bytes -> 4 full RTT waits of 150 ms between them.
        assertTrue(elapsedMillis >= 450, "5-window transfer should wait out 4 RTTs, took " + elapsedMillis + " ms");
        assertEquals(5, pacer.stats().windowsOpened());
        assertEquals(10_000, pacer.stats().lastWindowBytes());
        assertTrue(pacer.stats().totalParkedNanos() > 0);
    }

    @Test
    void readClampNeverExceedsWindowBudget() throws Exception {
        final PacingInputStream pacer = new PacingInputStream(
                new ByteArrayInputStream(new byte[5_000]), TimeUnit.SECONDS.toNanos(30), Long.MAX_VALUE, () -> 1_000);

        // A single large request must be clamped to the window budget, not served in full.
        final int n = pacer.read(new byte[5_000], 0, 5_000);
        assertEquals(1_000, n);
    }

    @Test
    void neverReturnsZeroAndLatchesEof() throws Exception {
        final PacingInputStream pacer = new PacingInputStream(
                new ByteArrayInputStream(new byte[25]), TimeUnit.MILLISECONDS.toNanos(20), Long.MAX_VALUE, () -> 10);

        final byte[] buffer = new byte[25];
        assertEquals(25, readFully(pacer, buffer));
        assertEquals(-1, pacer.read(buffer, 0, buffer.length), "end of stream must surface as -1");
        assertEquals(-1, pacer.read(buffer, 0, buffer.length), "EOF must be latched");
        assertEquals(0, pacer.read(buffer, 0, 0), "zero-length requests return 0 by contract");
    }

    @Test
    void liveWindowSupplierIsReadEachWindow() throws Exception {
        final AtomicInteger window = new AtomicInteger(100);
        final AtomicInteger supplierCalls = new AtomicInteger();
        final PacingInputStream pacer = new PacingInputStream(
                new ByteArrayInputStream(new byte[300]), ONE_HUNDRED_MILLIS, Long.MAX_VALUE, () -> {
                    supplierCalls.incrementAndGet();
                    return window.get();
                });

        final byte[] buffer = new byte[300];
        assertEquals(100, pacer.read(buffer, 0, buffer.length), "first window releases the initial W");

        window.set(200); // simulate kernel autotuning growing the buffer between windows
        assertEquals(200, pacer.read(buffer, 0, buffer.length), "second window must use the grown W");
        assertEquals(-1, pacer.read(buffer, 0, buffer.length), "stream exhausted after 300 bytes");

        assertEquals(200, pacer.stats().lastWindowBytes());
        assertEquals(supplierCalls.get(), pacer.stats().windowsOpened(), "supplier is read once per window open");
    }

    @Test
    void bandwidthCursorPacesWhenRttZero() throws Exception {
        final AtomicInteger supplierCalls = new AtomicInteger();
        final PacingInputStream pacer =
                new PacingInputStream(new ByteArrayInputStream(new byte[30_000]), 0, 100_000, () -> {
                    supplierCalls.incrementAndGet();
                    return Integer.MAX_VALUE;
                });

        final byte[] chunk = new byte[10_000];
        final long start = System.nanoTime();
        int total = 0;
        while (total < 30_000) {
            final int n = pacer.read(chunk, 0, chunk.length);
            if (n < 0) {
                break;
            }
            total += n;
        }
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(30_000, total);
        // 30_000 bytes at 100_000 B/s, release-then-wait: the two later chunks wait ~100 ms each.
        assertTrue(elapsedMillis >= 150, "bandwidth cursor should pace the transfer, took " + elapsedMillis + " ms");
        assertEquals(0, supplierCalls.get(), "rtt=0 disables the window mechanism entirely");
        assertEquals(0, pacer.stats().windowsOpened());
    }

    @Test
    void windowSupplierFailurePropagatesFromRead() {
        final PacingInputStream pacer = new PacingInputStream(
                new ByteArrayInputStream(new byte[100]), ONE_HUNDRED_MILLIS, Long.MAX_VALUE, () -> {
                    throw new IOException("window supplier boom");
                });

        final IOException thrown = assertThrows(IOException.class, () -> pacer.read(new byte[10], 0, 10));
        assertEquals("window supplier boom", thrown.getMessage());
    }

    /**
     * Recorded decision 3: a socket close does NOT wake a reader parked in the pacer; the reader exits cleanly once
     * the park expires (bounded by {@code max(RTT, one chunk's transmit time)}) and the next read hits the closed
     * socket.
     */
    @Test
    void closeDuringParkExitsWithinTheDocumentedBound() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            try (Socket client = new Socket("127.0.0.1", server.getLocalPort());
                    Socket accepted = server.accept()) {
                client.getOutputStream().write(new byte[5_000]);
                client.getOutputStream().flush();

                final PacingInputStream pacer = new PacingInputStream(
                        accepted.getInputStream(), TimeUnit.MILLISECONDS.toNanos(500), Long.MAX_VALUE, () -> 1_000);

                final AtomicReference<Throwable> thrown = new AtomicReference<>();
                final CountDownLatch firstWindowRead = new CountDownLatch(1);
                final Thread reader = new Thread(() -> {
                    final byte[] buffer = new byte[2_000];
                    try {
                        pacer.read(buffer, 0, buffer.length); // window 1 releases immediately
                        firstWindowRead.countDown();
                        pacer.read(buffer, 0, buffer.length); // budget exhausted -> parks ~one RTT
                    } catch (final Throwable t) {
                        thrown.set(t);
                        firstWindowRead.countDown();
                    }
                });
                reader.setDaemon(true);
                reader.start();

                assertTrue(firstWindowRead.await(5, TimeUnit.SECONDS), "first window must release promptly");
                Thread.sleep(100); // the reader is now parked; closing the socket will not wake it
                accepted.close();
                final long closedAt = System.nanoTime();
                reader.join(5_000);
                final long exitMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closedAt);

                assertFalse(reader.isAlive(), "reader must exit once the park expires");
                assertInstanceOf(IOException.class, thrown.get(), "post-park read of the closed socket must fail");
                assertTrue(exitMillis <= 2_000, "exit is bounded by ~one RTT plus slack, took " + exitMillis + " ms");
            }
        }
    }

    @Test
    void interruptDuringParkSurfacesAsIoException() throws Exception {
        final PacingInputStream pacer = new PacingInputStream(
                new ByteArrayInputStream(new byte[100]), TimeUnit.SECONDS.toNanos(60), Long.MAX_VALUE, () -> 10);

        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final CountDownLatch firstReadDone = new CountDownLatch(1);
        final Thread reader = new Thread(() -> {
            final byte[] buffer = new byte[50];
            try {
                pacer.read(buffer, 0, buffer.length); // releases window 1 immediately
                firstReadDone.countDown();
                pacer.read(buffer, 0, buffer.length); // budget exhausted -> parks for ~60 s
            } catch (final Throwable t) {
                thrown.set(t);
                firstReadDone.countDown();
            }
        });
        reader.setDaemon(true);
        reader.start();

        assertTrue(firstReadDone.await(5, TimeUnit.SECONDS), "first window must release promptly");
        Thread.sleep(100); // let the reader enter the park
        reader.interrupt();
        reader.join(5_000);

        assertFalse(reader.isAlive(), "interrupt must wake the parked reader");
        assertInstanceOf(IOException.class, thrown.get(), "interrupt during a pacing park surfaces as IOException");
    }
}
