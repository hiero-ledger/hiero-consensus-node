// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.model.node.NodeId;

/**
 * Real loopback TCP transport for ReconnectBench, with sockets configured through the production
 * {@link SocketFactory}.
 *
 * <p>Under {@link NetworkProfile#REALISTIC}, shaping is applied entirely on the <b>read side</b> by a
 * {@link PacingInputStream} per direction (see the socket-buffer read-pacing design). The write path is raw in all
 * profiles: write-side shaping would meter bytes before they reach the socket and keep the kernel send buffer
 * starved, hiding the very buffer behavior this transport exists to measure. Under {@link NetworkProfile#LOOPBACK}
 * both directions are completely unshaped (raw floor).
 */
public final class LoopbackSocketTransport implements AutoCloseable {

    private static final NodeId BENCHMARK_NODE_ID = NodeId.of(0);
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final ServerSocket serverSocket;
    private final Socket teacherSocket;
    private final Socket learnerSocket;

    private final CountingOutputStream teacherToLearnerWritten;
    private final CountingInputStream teacherToLearnerRead;
    private final CountingOutputStream learnerToTeacherWritten;
    private final CountingInputStream learnerToTeacherRead;

    /** Read-side pacers; {@code null} when the profile is {@link NetworkProfile#LOOPBACK} (raw floor). */
    private final PacingInputStream teacherToLearnerPacer;

    private final PacingInputStream learnerToTeacherPacer;

    private final DataOutputStream teacherOutput;
    private final DataInputStream teacherInput;
    private final DataOutputStream learnerOutput;
    private final DataInputStream learnerInput;
    private final SocketTransportDiagnostics diagnostics;

    public LoopbackSocketTransport(
            @NonNull final NetworkSimulationConfig config, @NonNull final Configuration configuration)
            throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
        serverSocket = new ServerSocket();
        SocketFactory.configureAndBind(
                BENCHMARK_NODE_ID, serverSocket, socketConfig, emptyGossipConfig(), 0);

        teacherSocket = new Socket();
        SocketFactory.configureAndConnect(
                teacherSocket, socketConfig, LOOPBACK_HOST, serverSocket.getLocalPort());

        learnerSocket = serverSocket.accept();
        learnerSocket.setTcpNoDelay(socketConfig.tcpNoDelay());
        learnerSocket.setSoTimeout(socketConfig.timeoutSyncClientSocket());
        // The accepted socket's send buffer is deliberately NOT set, matching production SocketFactory behavior
        // (recorded decision 5 of the read-pacing design).

        // Write path: raw socket output in all profiles (see class javadoc).
        teacherToLearnerWritten = new CountingOutputStream(teacherSocket.getOutputStream());
        teacherOutput = new DataOutputStream(
                new BufferedOutputStream(teacherToLearnerWritten, socketConfig.bufferSize()));

        // Read path: the pacer is the bottom-most wrapper, directly on the raw socket input, so bytes it
        // withholds stay in the kernel receive buffer. The effective window of a direction is the remote
        // sender's send buffer plus the local receiver's receive buffer, read live each window.
        final InputStream rawTeacherToLearner = learnerSocket.getInputStream();
        teacherToLearnerPacer = maybePace(
                rawTeacherToLearner,
                config,
                () -> teacherSocket.getSendBufferSize() + learnerSocket.getReceiveBufferSize());
        teacherToLearnerRead = new CountingInputStream(
                teacherToLearnerPacer != null ? teacherToLearnerPacer : rawTeacherToLearner);
        learnerInput =
                new DataInputStream(new BufferedInputStream(teacherToLearnerRead, socketConfig.bufferSize()));

        learnerToTeacherWritten = new CountingOutputStream(learnerSocket.getOutputStream());
        learnerOutput = new DataOutputStream(
                new BufferedOutputStream(learnerToTeacherWritten, socketConfig.bufferSize()));

        final InputStream rawLearnerToTeacher = teacherSocket.getInputStream();
        learnerToTeacherPacer = maybePace(
                rawLearnerToTeacher,
                config,
                () -> learnerSocket.getSendBufferSize() + teacherSocket.getReceiveBufferSize());
        learnerToTeacherRead = new CountingInputStream(
                learnerToTeacherPacer != null ? learnerToTeacherPacer : rawLearnerToTeacher);
        teacherInput =
                new DataInputStream(new BufferedInputStream(learnerToTeacherRead, socketConfig.bufferSize()));

        diagnostics = new SocketTransportDiagnostics(
                NetworkTransport.LOOPBACK_SOCKET,
                config.profile(),
                isLatencyShapingActive(config),
                isBandwidthShapingActive(config),
                config.latencyNanos(),
                config.bandwidthBytesPerSecond(),
                true,
                socketConfig.bufferSize(),
                serverSocket.getReceiveBufferSize(),
                teacherSocket.getSendBufferSize(),
                teacherSocket.getReceiveBufferSize(),
                learnerSocket.getSendBufferSize(),
                learnerSocket.getReceiveBufferSize(),
                teacherSocket.getTcpNoDelay(),
                learnerSocket.getTcpNoDelay());
    }

    public DataOutputStream getTeacherOutput() {
        return teacherOutput;
    }

    public DataInputStream getTeacherInput() {
        return teacherInput;
    }

    public DataOutputStream getLearnerOutput() {
        return learnerOutput;
    }

    public DataInputStream getLearnerInput() {
        return learnerInput;
    }

    public SimulatedNetworkStats getTeacherToLearnerStats() {
        return new SimulatedNetworkStats(teacherToLearnerWritten.count(), teacherToLearnerRead.count(), 0);
    }

    public SimulatedNetworkStats getLearnerToTeacherStats() {
        return new SimulatedNetworkStats(learnerToTeacherWritten.count(), learnerToTeacherRead.count(), 0);
    }

    public SocketTransportDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * End-of-run read-pacing summary: per-direction window count, last live window {@code W}, and total parked time.
     * Empty when pacing is inactive (LOOPBACK profile). The last window bytes are the live-{@code W} readout showing
     * what the kernel (including autotuning) actually granted; not to be confused with the ignored
     * {@code networkInflightBytesLimit} parameter.
     */
    public Optional<String> pacingSummary() {
        if (teacherToLearnerPacer == null) {
            return Optional.empty();
        }
        return Optional.of("teacherToLearner=" + format(teacherToLearnerPacer.stats())
                + ", learnerToTeacher=" + format(learnerToTeacherPacer.stats()));
    }

    public void disconnect() {
        closeQuietly(teacherSocket);
        closeQuietly(learnerSocket);
        closeQuietly(serverSocket);
    }

    @Override
    public void close() {
        closeQuietly(teacherOutput);
        closeQuietly(learnerOutput);
        closeQuietly(teacherInput);
        closeQuietly(learnerInput);
        closeQuietly(teacherSocket);
        closeQuietly(learnerSocket);
        closeQuietly(serverSocket);
    }

    private static GossipConfig emptyGossipConfig() {
        return new GossipConfig(List.of(), List.of(), 5, Duration.ofSeconds(60));
    }

    /** Whether read-side latency pacing (RTT-windowed release) is active. */
    private static boolean isLatencyShapingActive(final NetworkSimulationConfig config) {
        return config.profile() == NetworkProfile.REALISTIC && config.latencyNanos() > 0;
    }

    /** Whether read-side bandwidth pacing (release-then-wait cursor) is active. */
    private static boolean isBandwidthShapingActive(final NetworkSimulationConfig config) {
        return config.profile() == NetworkProfile.REALISTIC
                && config.bandwidthBytesPerSecond() != Long.MAX_VALUE;
    }

    /**
     * Wraps the raw socket input in a {@link PacingInputStream} under {@link NetworkProfile#REALISTIC}; returns
     * {@code null} under {@link NetworkProfile#LOOPBACK} so the raw floor stays byte-identical to an unshaped socket.
     * RTT is two times the configured one-way latency (a released window is "un-acked" for one round trip).
     */
    private static PacingInputStream maybePace(
            final InputStream raw,
            final NetworkSimulationConfig config,
            final PacingInputStream.WindowSupplier windowSupplier) {
        if (config.profile() != NetworkProfile.REALISTIC) {
            return null;
        }
        return new PacingInputStream(
                raw,
                Math.multiplyExact(2L, config.latencyNanos()),
                config.bandwidthBytesPerSecond(),
                windowSupplier);
    }

    private static String format(final PacingInputStream.PacingStats stats) {
        return "[windowsOpened=" + stats.windowsOpened()
                + ", lastWindowBytes=" + stats.lastWindowBytes()
                + ", totalParkedMillis=" + TimeUnit.NANOSECONDS.toMillis(stats.totalParkedNanos())
                + "]";
    }

    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
        }
    }
}
