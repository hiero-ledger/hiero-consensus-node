// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.junit.jupiter.api.Test;

class LoopbackSocketTransportTest {

    private static Configuration configuration() {
        return configuration(false);
    }

    private static Configuration configuration(final boolean gzipCompression) {
        return ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource()
                        .withValue("socket.tcpNoDelay", true)
                        .withValue("socket.gzipCompression", gzipCompression))
                .withConfigDataType(SocketConfig.class)
                .withConfigDataType(GossipConfig.class)
                .build();
    }

    private static SocketNetworkConfig loopbackConfig() {
        return SocketNetworkConfig.resolve(NetworkProfile.LOOPBACK, 0, 1);
    }

    private static SocketNetworkConfig realisticConfig(
            final long latencyMicroseconds, final long bandwidthMegabitsPerSecond) {
        return SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, latencyMicroseconds, bandwidthMegabitsPerSecond);
    }

    @Test
    void matchesVirtualMapPairedStreamsDirectionContract() throws Exception {
        try (PairedStreams streams = new PairedStreams()) {
            assertBidirectionalStreamContract(
                    streams.getTeacherInput(),
                    streams.getTeacherOutput(),
                    streams.getLearnerInput(),
                    streams.getLearnerOutput());
        }

        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration())) {
            assertBidirectionalStreamContract(
                    transport.getTeacherInput(),
                    transport.getTeacherOutput(),
                    transport.getLearnerInput(),
                    transport.getLearnerOutput());
        }
    }

    @Test
    void loopbackRoundTripsFramedBytesAndCountsThem() throws Exception {
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration())) {
            final DataOutputStream out = transport.getTeacherOutput();
            out.writeInt(4);
            out.write(new byte[] {1, 2, 3, 4});
            out.flush();

            final DataInputStream in = transport.getLearnerInput();
            assertEquals(4, in.readInt());
            final byte[] data = new byte[4];
            in.readFully(data);
            assertArrayEquals(new byte[] {1, 2, 3, 4}, data);

            final NetworkTransferStats stats = transport.getTeacherToLearnerStats();
            assertEquals(8, stats.bytesWritten());
            assertEquals(8, stats.bytesRead());
        }
    }

    @Test
    void gzipCompressionUsesCompressedWireBytes() throws Exception {
        final byte[] payload = new byte[64 * 1024];
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration(true))) {
            transport.getTeacherOutput().writeInt(payload.length);
            transport.getTeacherOutput().write(payload);
            transport.getTeacherOutput().flush();

            assertEquals(payload.length, transport.getLearnerInput().readInt());
            final byte[] received = new byte[payload.length];
            transport.getLearnerInput().readFully(received);
            assertArrayEquals(payload, received);

            assertTrue(
                    transport.getTeacherToLearnerStats().bytesWritten() < payload.length,
                    "compressible payload should use fewer wire bytes than its uncompressed size");
        }
    }

    @Test
    void diagnosticsExposeEffectiveSocketSettings() throws Exception {
        final Configuration configuration = configuration();
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration)) {
            final SocketTransportDiagnostics diagnostics = transport.diagnostics();

            assertEquals(NetworkProfile.LOOPBACK, diagnostics.profile());
            assertFalse(diagnostics.latencyShapingActive());
            assertFalse(diagnostics.bandwidthShapingActive());
            assertEquals(configuration.getConfigData(SocketConfig.class).bufferSize(), diagnostics.streamBufferBytes());
            assertTrue(diagnostics.serverReceiveBufferBytes() > 0);
            assertTrue(diagnostics.clientSendBufferBytes() > 0);
            assertTrue(diagnostics.clientReceiveBufferBytes() > 0);
            assertTrue(diagnostics.acceptedSendBufferBytes() > 0);
            assertTrue(diagnostics.acceptedReceiveBufferBytes() > 0);
            assertTrue(diagnostics.clientTcpNoDelay());
            assertTrue(diagnostics.acceptedTcpNoDelay());
        }
    }

    @Test
    void disconnectWakesBlockedReader() throws Exception {
        final LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration());
        try {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final CountDownLatch entered = new CountDownLatch(1);
            final Thread reader = new Thread(() -> {
                entered.countDown();
                try {
                    transport.getTeacherInput().read();
                } catch (final Throwable t) {
                    thrown.set(t);
                }
            });
            reader.setDaemon(true);
            reader.start();

            assertTrue(entered.await(5, TimeUnit.SECONDS), "reader thread should start");
            Thread.sleep(200);
            transport.disconnect();
            reader.join(5_000);

            assertFalse(reader.isAlive(), "disconnect should unblock the reader");
            assertTrue(thrown.get() instanceof IOException, "reader should fail with an IOException");
        } finally {
            transport.close();
        }
    }

    /**
     * Replaces the retired {@code realisticProfileDelaysFirstBytes}: with read-side pacing the write path is raw and
     * per-message first-byte delay is deliberately not modeled (recorded decision 6), so the windowed assertion is
     * that a transfer spanning several windows must wait out the RTT periods between them. The transfer size is
     * derived from the live diagnostics so the test holds for any local SocketFactory buffer experiment, with 6x
     * headroom against mid-run receive-buffer autotuning growing the window.
     */
    @Test
    void realisticProfileReleasesAtMostOneWindowPerRtt() throws Exception {
        // One-way 100 ms -> RTT 200 ms window period; bandwidth high enough that only the window paces.
        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(realisticConfig(100_000, 1_000_000), configuration())) {
            final SocketTransportDiagnostics diagnostics = transport.diagnostics();
            final int window = diagnostics.clientSendBufferBytes() + diagnostics.acceptedReceiveBufferBytes();
            // 12x the construction-time window: the pacer re-reads W live per window, so mid-run receive-buffer
            // autotuning would need to grow the average window >= 6x (far beyond the ~1.5x observed) before the
            // transfer could fit in under three windows and erode the elapsed bound below.
            final int total = 12 * window;

            final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            final Thread writer = new Thread(() -> {
                try {
                    final byte[] chunk = new byte[64 * 1024];
                    int remaining = total;
                    while (remaining > 0) {
                        final int n = Math.min(chunk.length, remaining);
                        transport.getTeacherOutput().write(chunk, 0, n);
                        remaining -= n;
                    }
                    transport.getTeacherOutput().flush();
                } catch (final Throwable t) {
                    writerFailure.set(t);
                }
            });
            writer.setDaemon(true);

            final long start = System.nanoTime();
            writer.start();
            transport.getLearnerInput().readFully(new byte[total]);
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            writer.join(10_000);

            // 6 windows require waiting out several full RTT periods even if autotuning doubles the window.
            assertTrue(
                    elapsedMillis >= 300,
                    "window pacing should spread a multi-window transfer across RTTs, took " + elapsedMillis + " ms");
            assertTrue(transport.diagnostics().latencyShapingActive());
            assertEquals(null, writerFailure.get(), "writer must complete cleanly");
        }
    }

    @Test
    void realisticProfilePacesLargeWrites() throws Exception {
        final byte[] payload = new byte[64 * 1024];
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(realisticConfig(0, 1), configuration())) {
            final long start = System.nanoTime();
            transport.getTeacherOutput().writeInt(payload.length);
            transport.getTeacherOutput().write(payload);
            transport.getTeacherOutput().flush();
            assertEquals(payload.length, transport.getLearnerInput().readInt());
            // Drain in bounded chunks, like the reconnect's framed messages. Release-then-wait pacing charges a
            // read's transmit time against the NEXT read, so a single huge readFully (BufferedInputStream's
            // large-read bypass) would arrive as one unpaced burst whose tail charge delays nothing; sustained
            // chunked flow — the benchmark's real shape — pays the full per-byte cost.
            final byte[] chunk = new byte[8 * 1024];
            for (int drained = 0; drained < payload.length; drained += chunk.length) {
                transport.getLearnerInput().readFully(chunk);
            }
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            // 64 KiB at 1 Mbps in 8 KiB chunks: ~7 inter-read waits of ~65 ms each -> ~460 ms end to end.
            assertTrue(elapsedMillis >= 300, "bandwidth pacing should pace a 64 KiB transfer at 1 Mbps");
            assertTrue(transport.diagnostics().bandwidthShapingActive());
        }
    }

    /**
     * Discriminates an accidentally re-introduced write-side shaper — the one regression every lower-bound timing
     * assertion in this suite would miss. Per recorded decision 6, latency is a throughput window only: a small
     * first message flows with ~zero added delay. A lingering write-side shaper would park one-way latency
     * (500 ms here) before the bytes even reach the socket, blowing the upper bound.
     */
    @Test
    void realisticProfileDoesNotDelayFirstBytes() throws Exception {
        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(realisticConfig(500_000, 1_000_000), configuration())) {
            final long start = System.nanoTime();
            transport.getTeacherOutput().writeInt(1234);
            transport.getTeacherOutput().flush();
            assertEquals(1234, transport.getLearnerInput().readInt());
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(
                    elapsedMillis < 450,
                    "first window must flow without per-message latency (decision 6), took " + elapsedMillis + " ms");
        }
    }

    @Test
    void pacingSummaryPresentOnlyWhenRealistic() throws Exception {
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration())) {
            assertTrue(transport.pacingSummary().isEmpty(), "LOOPBACK profile must stay a raw, unpaced floor");
        }
        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(realisticConfig(1_000, 1_000), configuration())) {
            assertTrue(transport.pacingSummary().isPresent(), "REALISTIC profile must expose pacing readouts");
        }
    }

    private static void assertBidirectionalStreamContract(
            final DataInputStream teacherInput,
            final DataOutputStream teacherOutput,
            final DataInputStream learnerInput,
            final DataOutputStream learnerOutput)
            throws IOException {
        teacherOutput.writeInt(1234);
        teacherOutput.flush();
        assertEquals(1234, learnerInput.readInt());

        learnerOutput.writeLong(5678L);
        learnerOutput.flush();
        assertEquals(5678L, teacherInput.readLong());
    }
}
