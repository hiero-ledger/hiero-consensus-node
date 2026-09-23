// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

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
 * A pair of teacher and learner streams connected through two {@link SimulatedNetworkChannel} instances, used by the
 * reconnect benchmark. The teacher writes to the teacher output and reads from the teacher input; the learner writes
 * to the learner output and reads from the learner input.
 *
 * <p>Each direction has an independent simulated channel configured with the same {@link NetworkSimulationConfig}.
 * This class exposes directional statistics and can disconnect both channels to wake blocked reconnect threads after
 * a failure.
 *
 * <p>Another {@code PairedStreams} class exists in {@code com.swirlds.virtualmap.test.fixtures.sync}. That test fixture
 * uses real loopback sockets, whereas this benchmark-specific implementation must apply simulated latency, bandwidth,
 * and backpressure and report the resulting network statistics. The two implementations are therefore kept separate
 * for now despite their common stream-pairing API.
 */
public class PairedStreams implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(PairedStreams.class);

    private final DataOutputStream teacherOutput;
    private final DataInputStream teacherInput;
    private final DataOutputStream learnerOutput;
    private final DataInputStream learnerInput;

    private final SimulatedNetworkChannel teacherToLearner;
    private final SimulatedNetworkChannel learnerToTeacher;

    /**
     * Creates paired reconnect streams over two simulated network channels.
     *
     * @param networkConfig the simulation configuration applied independently to both directions
     */
    public PairedStreams(@NonNull final NetworkSimulationConfig networkConfig) {
        teacherToLearner = new SimulatedNetworkChannel(networkConfig);
        learnerToTeacher = new SimulatedNetworkChannel(networkConfig);

        teacherOutput = new DataOutputStream(new BufferedOutputStream(teacherToLearner.outputStream()));
        teacherInput = new DataInputStream(new BufferedInputStream(learnerToTeacher.inputStream()));
        learnerOutput = new DataOutputStream(new BufferedOutputStream(learnerToTeacher.outputStream()));
        learnerInput = new DataInputStream(new BufferedInputStream(teacherToLearner.inputStream()));
    }

    /**
     * Returns the teacher's output stream, whose bytes are read from the learner input.
     *
     * @return the teacher output stream
     */
    public DataOutputStream getTeacherOutput() {
        return teacherOutput;
    }

    /**
     * Returns the teacher's input stream, which reads bytes written to the learner output.
     *
     * @return the teacher input stream
     */
    public DataInputStream getTeacherInput() {
        return teacherInput;
    }

    /**
     * Returns the learner's output stream, whose bytes are read from the teacher input.
     *
     * @return the learner output stream
     */
    public DataOutputStream getLearnerOutput() {
        return learnerOutput;
    }

    /**
     * Returns the learner's input stream, which reads bytes written to the teacher output.
     *
     * @return the learner input stream
     */
    public DataInputStream getLearnerInput() {
        return learnerInput;
    }

    /**
     * Returns a snapshot of the teacher-to-learner channel statistics.
     *
     * @return the current teacher-to-learner statistics
     */
    public SimulatedNetworkStats getTeacherToLearnerStats() {
        return teacherToLearner.snapshotStats();
    }

    /**
     * Returns a snapshot of the learner-to-teacher channel statistics.
     *
     * @return the current learner-to-teacher statistics
     */
    public SimulatedNetworkStats getLearnerToTeacherStats() {
        return learnerToTeacher.snapshotStats();
    }

    /**
     * Closes the four outer data streams, which also close their buffered streams and simulated channel endpoints.
     * Closing is best-effort: failures are logged so they do not mask a completed benchmark result, and all remaining
     * streams are still closed.
     */
    @Override
    public void close() {
        final List<Closeable> toClose = List.of(teacherOutput, teacherInput, learnerOutput, learnerInput);
        for (final Closeable stream : toClose) {
            try {
                stream.close();
            } catch (final IOException e) {
                logger.error("Error while closing reconnect benchmark stream", e);
            }
        }
    }

    /**
     * Aborts both simulated channels, waking threads blocked while reading, writing, or waiting for simulated timing.
     */
    public void disconnect() {
        teacherToLearner.disconnect();
        learnerToTeacher.disconnect();
    }
}
