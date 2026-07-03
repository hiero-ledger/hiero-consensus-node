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
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.model.node.NodeId;

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

        teacherToLearnerWritten = new CountingOutputStream(teacherSocket.getOutputStream());
        teacherOutput = new DataOutputStream(
                new BufferedOutputStream(teacherToLearnerWritten, socketConfig.bufferSize()));
        teacherToLearnerRead = new CountingInputStream(learnerSocket.getInputStream());
        learnerInput =
                new DataInputStream(new BufferedInputStream(teacherToLearnerRead, socketConfig.bufferSize()));

        learnerToTeacherWritten = new CountingOutputStream(learnerSocket.getOutputStream());
        learnerOutput = new DataOutputStream(
                new BufferedOutputStream(learnerToTeacherWritten, socketConfig.bufferSize()));
        learnerToTeacherRead = new CountingInputStream(teacherSocket.getInputStream());
        teacherInput =
                new DataInputStream(new BufferedInputStream(learnerToTeacherRead, socketConfig.bufferSize()));

        diagnostics = new SocketTransportDiagnostics(
                NetworkTransport.LOOPBACK_SOCKET,
                config.profile(),
                false,
                false,
                config.latencyNanos(),
                config.bandwidthBytesPerSecond(),
                true,
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

    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
        }
    }
}
