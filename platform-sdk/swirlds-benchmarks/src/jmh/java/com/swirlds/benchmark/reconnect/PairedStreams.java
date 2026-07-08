// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.benchmark.reconnect.network.LoopbackSocketTransport;
import com.swirlds.benchmark.reconnect.network.NetworkSimulationConfig;
import com.swirlds.benchmark.reconnect.network.NetworkTransport;
import com.swirlds.benchmark.reconnect.network.SocketTransportDiagnostics;
import com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannel;
import com.swirlds.benchmark.reconnect.network.SimulatedNetworkStats;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for generating paired streams for synchronization tests.
 */
public class PairedStreams implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(PairedStreams.class);

    private BufferedOutputStream teacherOutputBuffer;
    private DataOutputStream teacherOutput;

    private BufferedInputStream teacherInputBuffer;
    private DataInputStream teacherInput;

    private BufferedOutputStream learnerOutputBuffer;
    private DataOutputStream learnerOutput;
    private BufferedInputStream learnerInputBuffer;
    private DataInputStream learnerInput;

    private final NetworkTransport transport;
    private final SimulatedNetworkChannel teacherToLearner;
    private final SimulatedNetworkChannel learnerToTeacher;
    private final LoopbackSocketTransport socketTransport;

    public PairedStreams(
            @NonNull final NetworkTransport transport,
            @NonNull final NetworkSimulationConfig networkConfig,
            @NonNull final Configuration configuration)
            throws IOException {
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(networkConfig, "networkConfig must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        this.transport = transport;

        if (transport == NetworkTransport.SIMULATED) {
            socketTransport = null;
            teacherToLearner = new SimulatedNetworkChannel(networkConfig);
            learnerToTeacher = new SimulatedNetworkChannel(networkConfig);

            teacherOutputBuffer = new BufferedOutputStream(teacherToLearner.outputStream());
            teacherOutput = new DataOutputStream(teacherOutputBuffer);

            teacherInputBuffer = new BufferedInputStream(learnerToTeacher.inputStream());
            teacherInput = new DataInputStream(teacherInputBuffer);

            learnerOutputBuffer = new BufferedOutputStream(learnerToTeacher.outputStream());
            learnerOutput = new DataOutputStream(learnerOutputBuffer);

            learnerInputBuffer = new BufferedInputStream(teacherToLearner.inputStream());
            learnerInput = new DataInputStream(learnerInputBuffer);
            return;
        }

        teacherToLearner = null;
        learnerToTeacher = null;
        socketTransport = new LoopbackSocketTransport(networkConfig, configuration);

        teacherOutput = socketTransport.getTeacherOutput();
        teacherInput = socketTransport.getTeacherInput();
        learnerOutput = socketTransport.getLearnerOutput();
        learnerInput = socketTransport.getLearnerInput();
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
        return switch (transport) {
            case SIMULATED -> teacherToLearner.snapshotStats();
            case LOOPBACK_SOCKET -> socketTransport.getTeacherToLearnerStats();
        };
    }

    public SimulatedNetworkStats getLearnerToTeacherStats() {
        return switch (transport) {
            case SIMULATED -> learnerToTeacher.snapshotStats();
            case LOOPBACK_SOCKET -> socketTransport.getLearnerToTeacherStats();
        };
    }

    public Optional<SocketTransportDiagnostics> getSocketDiagnostics() {
        return transport == NetworkTransport.LOOPBACK_SOCKET
                ? Optional.of(socketTransport.diagnostics())
                : Optional.empty();
    }

    /**
     * End-of-run read-pacing summary for the socket transport (live window readouts); empty for the simulated
     * transport or when pacing is inactive (LOOPBACK profile).
     */
    public Optional<String> getSocketPacingSummary() {
        return transport == NetworkTransport.LOOPBACK_SOCKET ? socketTransport.pacingSummary() : Optional.empty();
    }

    @Override
    public void close() throws IOException {
        if (transport == NetworkTransport.LOOPBACK_SOCKET) {
            socketTransport.close();
            return;
        }

        final List<Closeable> toClose = List.of(
                teacherOutput,
                teacherInput,
                learnerOutput,
                learnerInput,
                teacherOutputBuffer,
                teacherInputBuffer,
                learnerOutputBuffer,
                learnerInputBuffer);
        for (final Closeable c : toClose) {
            try {
                c.close();
            } catch (final Exception e) {
                // this is the test code, and we don't want the test to fail because of a close error
                logger.error("Error while closing resources", e);
            }
        }
    }

    /**
     * Do an emergency shutdown of the simulated channels. Intentionally pulls the rug out from underneath all streams
     * reading/writing the channels.
     */
    public void disconnect() {
        if (transport == NetworkTransport.LOOPBACK_SOCKET) {
            socketTransport.disconnect();
            return;
        }
        teacherToLearner.disconnect();
        learnerToTeacher.disconnect();
    }
}
