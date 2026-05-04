// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.benchmark.reconnect.network.NetworkSimulationConfig;
import com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannel;
import com.swirlds.benchmark.reconnect.network.SimulatedNetworkStats;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
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

    private final SimulatedNetworkChannel teacherToLearner;
    private final SimulatedNetworkChannel learnerToTeacher;

    public PairedStreams(@NonNull final NetworkSimulationConfig networkConfig) {
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
        return teacherToLearner.snapshotStats();
    }

    public SimulatedNetworkStats getLearnerToTeacherStats() {
        return learnerToTeacher.snapshotStats();
    }

    @Override
    public void close() throws IOException {
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
        teacherToLearner.disconnect();
        learnerToTeacher.disconnect();
    }
}
