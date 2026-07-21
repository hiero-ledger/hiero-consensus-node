// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.model.node.NodeId;

/**
 * One production-option-configured, full-duplex loopback TCP connection for {@code ReconnectBench}.
 *
 * <p>{@link NetworkProfile#REALISTIC} installs refined-A1 below the production sync/compression streams: an output
 * observer publishes bounded compressed-payload ranges immediately before each raw socket write, and the opposite
 * input gate consumes only ranges whose sender-relative latency/bandwidth schedule is eligible. The controller owns
 * metadata only; the real socket remains the payload store and the capacity/backpressure authority.
 *
 * <p>{@link NetworkProfile#INSTRUMENTED_LOOPBACK} installs identical observer/gate/range plumbing without timing
 * waits. {@link NetworkProfile#LOOPBACK} installs no refined-A1 components and remains the raw socket floor.
 */
public final class LoopbackSocketTransport implements AutoCloseable {

    private static final NodeId BENCHMARK_NODE_ID = NodeId.of(0);
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final ServerSocket serverSocket;
    private final Socket teacherSocket;
    private final Socket learnerSocket;
    private final SocketVisibilityController teacherToLearnerController;
    private final SocketVisibilityController learnerToTeacherController;
    private final AtomicBoolean connectionTerminated = new AtomicBoolean();

    private final SyncOutputStream teacherSyncOutput;
    private final SyncInputStream teacherSyncInput;
    private final SyncOutputStream learnerSyncOutput;
    private final SyncInputStream learnerSyncInput;

    private final DataOutputStream teacherOutput;
    private final DataInputStream teacherInput;
    private final DataOutputStream learnerOutput;
    private final DataInputStream learnerInput;
    private final SocketTransportDiagnostics diagnostics;

    public LoopbackSocketTransport(
            @NonNull final SocketNetworkConfig config, @NonNull final Configuration configuration) throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
        // Validate and construct metadata-only controllers before acquiring socket resources.
        teacherToLearnerController =
                config.visibilitySchedulingActive() ? new SocketVisibilityController(config) : null;
        learnerToTeacherController =
                config.visibilitySchedulingActive() ? new SocketVisibilityController(config) : null;

        final ConnectedSockets sockets = ConnectedSockets.open(socketConfig);
        serverSocket = sockets.serverSocket();
        teacherSocket = sockets.teacherSocket();
        learnerSocket = sockets.learnerSocket();

        try {
            final OutputStream rawTeacherOutput = teacherSocket.getOutputStream();
            final OutputStream teacherTransportOutput = teacherToLearnerController == null
                    ? rawTeacherOutput
                    : new ObservedSocketOutputStream(
                            rawTeacherOutput, teacherToLearnerController, this::abortConnection);
            teacherSyncOutput = SyncOutputStream.createSyncOutputStream(
                    configuration, teacherTransportOutput, socketConfig.bufferSize());
            teacherOutput = new DataOutputStream(teacherSyncOutput);

            final InputStream rawTeacherToLearnerInput = learnerSocket.getInputStream();
            final InputStream learnerTransportInput = teacherToLearnerController == null
                    ? rawTeacherToLearnerInput
                    : new ScheduledSocketInputStream(
                            rawTeacherToLearnerInput,
                            learnerSocket,
                            socketConfig.timeoutSyncClientSocket(),
                            teacherToLearnerController,
                            this::abortConnection);
            learnerSyncInput = SyncInputStream.createSyncInputStream(
                    configuration, learnerTransportInput, socketConfig.bufferSize());
            learnerInput = new DataInputStream(learnerSyncInput);

            final OutputStream rawLearnerOutput = learnerSocket.getOutputStream();
            final OutputStream learnerTransportOutput = learnerToTeacherController == null
                    ? rawLearnerOutput
                    : new ObservedSocketOutputStream(
                            rawLearnerOutput, learnerToTeacherController, this::abortConnection);
            learnerSyncOutput = SyncOutputStream.createSyncOutputStream(
                    configuration, learnerTransportOutput, socketConfig.bufferSize());
            learnerOutput = new DataOutputStream(learnerSyncOutput);

            final InputStream rawLearnerToTeacherInput = teacherSocket.getInputStream();
            final InputStream teacherTransportInput = learnerToTeacherController == null
                    ? rawLearnerToTeacherInput
                    : new ScheduledSocketInputStream(
                            rawLearnerToTeacherInput,
                            teacherSocket,
                            socketConfig.timeoutSyncClientSocket(),
                            learnerToTeacherController,
                            this::abortConnection);
            teacherSyncInput = SyncInputStream.createSyncInputStream(
                    configuration, teacherTransportInput, socketConfig.bufferSize());
            teacherInput = new DataInputStream(teacherSyncInput);

            diagnostics = new SocketTransportDiagnostics(
                    config.profile(),
                    config.visibilitySchedulingActive(),
                    config.latencyShapingActive(),
                    config.bandwidthShapingActive(),
                    config.configuredLatencyNanos(),
                    config.configuredBandwidthBytesPerSecond(),
                    config.modeledLatencyNanos(),
                    config.modeledBandwidthBytesPerSecond(),
                    config.releaseQuantumNanos(),
                    config.maxObservedRangeBytes(),
                    socketConfig.bufferSize(),
                    serverSocket.getReceiveBufferSize(),
                    teacherSocket.getSendBufferSize(),
                    teacherSocket.getReceiveBufferSize(),
                    learnerSocket.getSendBufferSize(),
                    learnerSocket.getReceiveBufferSize(),
                    teacherSocket.getTcpNoDelay(),
                    learnerSocket.getTcpNoDelay());
        } catch (final IOException | RuntimeException e) {
            abortConnection(new IOException("Unable to construct loopback socket transport streams", e));
            throw e;
        }
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

    public NetworkTransferStats getTeacherToLearnerStats() {
        return new NetworkTransferStats(
                teacherSyncOutput.connectionByteCounter().getCount(),
                learnerSyncInput.byteCounter().getCount());
    }

    public NetworkTransferStats getLearnerToTeacherStats() {
        return new NetworkTransferStats(
                learnerSyncOutput.connectionByteCounter().getCount(),
                teacherSyncInput.byteCounter().getCount());
    }

    public SocketTransportDiagnostics diagnostics() {
        return diagnostics;
    }

    /** Returns directional refined-A1 diagnostics, or empty for the raw {@code LOOPBACK} profile. */
    public Optional<SocketVisibilityStats> teacherToLearnerVisibilityStats() {
        return teacherToLearnerController == null ? Optional.empty() : Optional.of(teacherToLearnerController.stats());
    }

    /** Returns directional refined-A1 diagnostics, or empty for the raw {@code LOOPBACK} profile. */
    public Optional<SocketVisibilityStats> learnerToTeacherVisibilityStats() {
        return learnerToTeacherController == null ? Optional.empty() : Optional.of(learnerToTeacherController.stats());
    }

    /** Compact end-of-run controller summary for benchmark logs. */
    public Optional<String> visibilitySummary() {
        if (teacherToLearnerController == null) {
            return Optional.empty();
        }
        return Optional.of("teacherToLearner=" + teacherToLearnerController.stats()
                + ", learnerToTeacher=" + learnerToTeacherController.stats()
                + ", endSocketBuffers=" + currentSocketBufferSummary());
    }

    /** Marks a successful protocol run complete before its final diagnostics snapshot is logged. */
    public void complete() {
        beginControllerCleanup();
    }

    /** Aborts both directions and physically closes the one full-duplex connection. */
    public void disconnect() {
        abortConnection(new IOException("Reconnect benchmark requested socket disconnect"));
    }

    @Override
    public void close() {
        if (connectionTerminated.compareAndSet(false, true)) {
            beginControllerCleanup();
            closeRawConnection();
        }
        // Raw closure happens first so a blocked write cannot make compression/output cleanup hang.
        closeQuietly(teacherOutput);
        closeQuietly(learnerOutput);
        closeQuietly(teacherInput);
        closeQuietly(learnerInput);
    }

    private void abortConnection(final IOException failure) {
        if (!connectionTerminated.compareAndSet(false, true)) {
            return;
        }
        // Fixed order; never hold both controller locks at once.
        if (teacherToLearnerController != null) {
            teacherToLearnerController.abort(failure);
        }
        if (learnerToTeacherController != null) {
            learnerToTeacherController.abort(failure);
        }
        closeRawConnection();
    }

    private void beginControllerCleanup() {
        if (teacherToLearnerController != null) {
            teacherToLearnerController.beginCleanup();
        }
        if (learnerToTeacherController != null) {
            learnerToTeacherController.beginCleanup();
        }
    }

    private void closeRawConnection() {
        closeQuietly(teacherSocket);
        closeQuietly(learnerSocket);
        closeQuietly(serverSocket);
    }

    private String currentSocketBufferSummary() {
        try {
            return "[teacherSend=" + teacherSocket.getSendBufferSize()
                    + ", teacherReceive=" + teacherSocket.getReceiveBufferSize()
                    + ", learnerSend=" + learnerSocket.getSendBufferSize()
                    + ", learnerReceive=" + learnerSocket.getReceiveBufferSize()
                    + "]";
        } catch (final IOException e) {
            return "unavailable:" + e.getMessage();
        }
    }

    private static GossipConfig emptyGossipConfig() {
        return new GossipConfig(List.of(), List.of(), 5, Duration.ofSeconds(60));
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (final IOException ignored) {
            // Connection shutdown is best effort and deliberately idempotent.
        }
    }

    /** Owns partial-construction cleanup while establishing the production-configured socket pair. */
    private record ConnectedSockets(ServerSocket serverSocket, Socket teacherSocket, Socket learnerSocket) {

        private static ConnectedSockets open(final SocketConfig socketConfig) throws IOException {
            ServerSocket server = null;
            Socket teacher = null;
            Socket learner = null;
            try {
                server = new ServerSocket();
                SocketFactory.configureAndBind(BENCHMARK_NODE_ID, server, socketConfig, emptyGossipConfig(), 0);

                teacher = new Socket();
                SocketFactory.configureAndConnect(teacher, socketConfig, LOOPBACK_HOST, server.getLocalPort());

                learner = server.accept();
                learner.setTcpNoDelay(socketConfig.tcpNoDelay());
                learner.setSoTimeout(socketConfig.timeoutSyncClientSocket());
                // Deliberately do not set the accepted socket's send buffer, matching production SocketFactory.
                return new ConnectedSockets(server, teacher, learner);
            } catch (final IOException | RuntimeException e) {
                closeQuietly(teacher);
                closeQuietly(learner);
                closeQuietly(server);
                throw e;
            }
        }
    }
}
