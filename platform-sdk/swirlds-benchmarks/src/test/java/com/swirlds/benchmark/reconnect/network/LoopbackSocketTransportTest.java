// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.junit.jupiter.api.Test;

class LoopbackSocketTransportTest {

    private static final long GENEROUS_WALL_TIME_MILLIS = 5_000;

    private static Configuration configuration() {
        return configuration(false);
    }

    private static Configuration configuration(final boolean gzipCompression) {
        return ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource()
                        .withValue("socket.tcpNoDelay", true)
                        .withValue("socket.gzipCompression", gzipCompression)
                        .withValue("socket.timeoutSyncClientSocket", 10_000))
                .withConfigDataType(SocketConfig.class)
                .withConfigDataType(GossipConfig.class)
                .build();
    }

    private static SocketNetworkConfig config(
            final NetworkProfile profile, final long latencyMicroseconds, final long bandwidthMegabitsPerSecond) {
        return SocketNetworkConfig.resolve(profile, latencyMicroseconds, bandwidthMegabitsPerSecond);
    }

    @Test
    void rawLoopbackMatchesVirtualMapPairedStreamsDirectionContract() throws Exception {
        try (PairedStreams streams = new PairedStreams()) {
            assertBidirectionalStreamContract(
                    streams.getTeacherInput(),
                    streams.getTeacherOutput(),
                    streams.getLearnerInput(),
                    streams.getLearnerOutput());
        }

        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(config(NetworkProfile.LOOPBACK, 270, 200), configuration())) {
            assertBidirectionalStreamContract(
                    transport.getTeacherInput(),
                    transport.getTeacherOutput(),
                    transport.getLearnerInput(),
                    transport.getLearnerOutput());

            assertTrue(transport.teacherToLearnerVisibilityStats().isEmpty());
            assertTrue(transport.learnerToTeacherVisibilityStats().isEmpty());
            assertTrue(transport.visibilitySummary().isEmpty());
        }
    }

    @Test
    void realisticProfileDelaysFirstByteByConfiguredOneWayLatency() throws Exception {
        final long oneWayLatencyMillis = 100;
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(
                config(NetworkProfile.REALISTIC, TimeUnit.MILLISECONDS.toMicros(oneWayLatencyMillis), 10_000),
                configuration())) {
            final long started = System.nanoTime();
            transport.getTeacherOutput().writeInt(1234);
            transport.getTeacherOutput().flush();
            assertEquals(1234, transport.getLearnerInput().readInt());
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            // Wall-clock integration checks are deliberately broad; exact deadlines are tested with a fake clock.
            assertTrue(elapsedMillis >= 70, "first byte was visible too early: " + elapsedMillis + " ms");
            assertTrue(
                    elapsedMillis < GENEROUS_WALL_TIME_MILLIS,
                    "first-byte shaping should not stall the connection: " + elapsedMillis + " ms");
        }
    }

    @Test
    void realisticProfileProgressivelyReleasesAtConfiguredBandwidth() throws Exception {
        final byte[] payload = new byte[256 * 1024];
        final AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        // 256 KiB at 20 Mbit/s is about 105 ms. A concurrent writer avoids relying on local socket capacity.
        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(config(NetworkProfile.REALISTIC, 0, 20), configuration())) {
            final Thread writer = new Thread(() -> {
                try {
                    transport.getTeacherOutput().writeInt(payload.length);
                    transport.getTeacherOutput().write(payload);
                    transport.getTeacherOutput().flush();
                } catch (final Throwable failure) {
                    writerFailure.set(failure);
                }
            });
            writer.setDaemon(true);

            final long started = System.nanoTime();
            writer.start();
            assertEquals(payload.length, transport.getLearnerInput().readInt());
            final byte[] received = new byte[payload.length];
            transport.getLearnerInput().readFully(received);
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            writer.join(GENEROUS_WALL_TIME_MILLIS);

            assertArrayEquals(payload, received);
            assertFalse(writer.isAlive(), "writer should finish after the receiver drains the connection");
            assertNull(writerFailure.get(), "writer must complete cleanly");
            assertTrue(elapsedMillis >= 70, "payload was released faster than the broad bandwidth bound");
            assertTrue(elapsedMillis < GENEROUS_WALL_TIME_MILLIS, "bandwidth shaping unexpectedly stalled");
        }
    }

    @Test
    void realisticProfileAppliesOneWayLatencyOncePerPingPongLeg() throws Exception {
        final long oneWayLatencyMillis = 40;
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(
                config(NetworkProfile.REALISTIC, TimeUnit.MILLISECONDS.toMicros(oneWayLatencyMillis), 10_000),
                configuration())) {
            final long started = System.nanoTime();
            transport.getTeacherOutput().writeInt(1);
            transport.getTeacherOutput().flush();
            assertEquals(1, transport.getLearnerInput().readInt());
            transport.getLearnerOutput().writeInt(2);
            transport.getLearnerOutput().flush();
            assertEquals(2, transport.getTeacherInput().readInt());
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis >= 55, "two sequential legs should pay approximately two one-way latencies");
            assertTrue(elapsedMillis < GENEROUS_WALL_TIME_MILLIS);
        }
    }

    @Test
    void fullDuplexDirectionsHaveIndependentVisibilitySchedules() throws Exception {
        final long oneWayLatencyMillis = 50;
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(
                config(NetworkProfile.REALISTIC, TimeUnit.MILLISECONDS.toMicros(oneWayLatencyMillis), 10_000),
                configuration())) {
            final long started = System.nanoTime();
            transport.getTeacherOutput().writeInt(11);
            transport.getLearnerOutput().writeInt(22);
            transport.getTeacherOutput().flush();
            transport.getLearnerOutput().flush();

            assertEquals(11, transport.getLearnerInput().readInt());
            assertEquals(22, transport.getTeacherInput().readInt());
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis >= 35, "both directions must be gated");
            assertTrue(elapsedMillis < GENEROUS_WALL_TIME_MILLIS, "full duplex schedules must not deadlock");
            assertEquals(
                    4, transport.teacherToLearnerVisibilityStats().orElseThrow().returnedBytes());
            assertEquals(
                    4, transport.learnerToTeacherVisibilityStats().orElseThrow().returnedBytes());
        }
    }

    @Test
    void instrumentedLoopbackUsesSamePlumbingWithoutTimingShaping() throws Exception {
        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(config(NetworkProfile.INSTRUMENTED_LOOPBACK, 270, 200), configuration())) {
            assertBidirectionalStreamContract(
                    transport.getTeacherInput(),
                    transport.getTeacherOutput(),
                    transport.getLearnerInput(),
                    transport.getLearnerOutput());

            final SocketTransportDiagnostics diagnostics = transport.diagnostics();
            assertTrue(diagnostics.visibilitySchedulingActive());
            assertFalse(diagnostics.latencyShapingActive());
            assertFalse(diagnostics.bandwidthShapingActive());
            assertEquals(0, diagnostics.modeledLatencyNanos());
            assertEquals(Long.MAX_VALUE, diagnostics.modeledBandwidthBytesPerSecond());
            assertTrue(transport.visibilitySummary().isPresent());
            assertEquals(
                    4, transport.teacherToLearnerVisibilityStats().orElseThrow().returnedBytes());
            assertEquals(
                    8, transport.learnerToTeacherVisibilityStats().orElseThrow().returnedBytes());
        }
    }

    @Test
    void productionCompressionCountersAndVisibilityDiagnosticsDescribeWireBytes() throws Exception {
        final byte[] payload = new byte[64 * 1024];
        Arrays.fill(payload, (byte) 7);
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(
                config(NetworkProfile.INSTRUMENTED_LOOPBACK, 270, 200), configuration(true))) {
            transport.getTeacherOutput().writeInt(payload.length);
            transport.getTeacherOutput().write(payload);
            transport.getTeacherOutput().flush();

            assertEquals(payload.length, transport.getLearnerInput().readInt());
            final byte[] received = new byte[payload.length];
            transport.getLearnerInput().readFully(received);
            assertArrayEquals(payload, received);

            final NetworkTransferStats transfer = transport.getTeacherToLearnerStats();
            final SocketVisibilityStats visibility =
                    transport.teacherToLearnerVisibilityStats().orElseThrow();
            assertTrue(transfer.bytesWritten() < payload.length, "compressible data should use fewer wire bytes");
            assertTrue(transfer.bytesRead() > 0);
            assertEquals(transfer.bytesWritten(), visibility.observedBytes());
            assertEquals(transfer.bytesRead(), visibility.returnedBytes());
            assertEquals(0, visibility.pendingBytes());
            assertEquals("ACTIVE", visibility.state());
            assertTrue(visibility.rangeCount() > 0);
            assertTrue(visibility.rawWriteCount() > 0);
            assertTrue(visibility.rawReadCount() > 0);
        }
    }

    @Test
    void diagnosticsExposeConfiguredModeledAndEffectiveSocketSettings() throws Exception {
        final SocketNetworkConfig config = config(NetworkProfile.REALISTIC, 270, 200);
        final Configuration configuration = configuration();
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(config, configuration)) {
            final SocketTransportDiagnostics diagnostics = transport.diagnostics();

            assertEquals(NetworkProfile.REALISTIC, diagnostics.profile());
            assertTrue(diagnostics.visibilitySchedulingActive());
            assertTrue(diagnostics.latencyShapingActive());
            assertTrue(diagnostics.bandwidthShapingActive());
            assertEquals(config.configuredLatencyNanos(), diagnostics.configuredLatencyNanos());
            assertEquals(config.modeledLatencyNanos(), diagnostics.modeledLatencyNanos());
            assertEquals(config.releaseQuantumNanos(), diagnostics.releaseQuantumNanos());
            assertEquals(config.maxObservedRangeBytes(), diagnostics.maxObservedRangeBytes());
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
    void disconnectWakesReaderWaitingForSenderMetadata() throws Exception {
        final LoopbackSocketTransport transport =
                new LoopbackSocketTransport(config(NetworkProfile.REALISTIC, 100_000, 200), configuration());
        try {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final CountDownLatch entered = new CountDownLatch(1);
            final Thread reader = new Thread(() -> {
                entered.countDown();
                try {
                    transport.getTeacherInput().read();
                } catch (final Throwable failure) {
                    thrown.set(failure);
                }
            });
            reader.setDaemon(true);
            reader.start();

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            transport.disconnect();
            reader.join(GENEROUS_WALL_TIME_MILLIS);

            assertFalse(reader.isAlive(), "disconnect must wake a metadata waiter");
            assertTrue(thrown.get() instanceof IOException, "reader should fail with an IOException");
            assertTrue(transport
                    .teacherToLearnerVisibilityStats()
                    .orElseThrow()
                    .state()
                    .startsWith("ABORTED"));
            assertTrue(transport
                    .learnerToTeacherVisibilityStats()
                    .orElseThrow()
                    .state()
                    .startsWith("ABORTED"));
        } finally {
            transport.close();
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
