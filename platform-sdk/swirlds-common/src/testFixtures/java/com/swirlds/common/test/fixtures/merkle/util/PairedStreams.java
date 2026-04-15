// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * A pair of streams connected via a loopback socket, used in reconnect tests.
 * The teacher writes to the teacher output and reads from the teacher input;
 * the learner writes to the learner output and reads from the learner input.
 */
public class PairedStreams implements AutoCloseable {

    protected BufferedOutputStream teacherOutputBuffer;
    protected DataOutputStream teacherOutput;

    protected BufferedInputStream teacherInputBuffer;
    protected DataInputStream teacherInput;

    protected BufferedOutputStream learnerOutputBuffer;
    protected DataOutputStream learnerOutput;
    protected BufferedInputStream learnerInputBuffer;
    protected DataInputStream learnerInput;

    protected Socket teacherSocket;
    protected Socket learnerSocket;
    protected ServerSocket server;

    /**
     * Create a new pair of connected streams over a loopback socket.
     *
     * @throws IOException if the socket setup fails
     */
    public PairedStreams() throws IOException {

        server = new ServerSocket(0);
        teacherSocket = new Socket("127.0.0.1", server.getLocalPort());
        learnerSocket = server.accept();

        teacherOutputBuffer = new BufferedOutputStream(teacherSocket.getOutputStream());
        teacherOutput = new DataOutputStream(teacherOutputBuffer);

        teacherInputBuffer = new BufferedInputStream(teacherSocket.getInputStream());
        teacherInput = new DataInputStream(teacherInputBuffer);

        learnerOutputBuffer = new BufferedOutputStream(learnerSocket.getOutputStream());
        learnerOutput = new DataOutputStream(learnerOutputBuffer);

        learnerInputBuffer = new BufferedInputStream(learnerSocket.getInputStream());
        learnerInput = new DataInputStream(learnerInputBuffer);
    }

    /**
     * Returns the teacher's output stream (teacher writes here, learner reads from its input).
     *
     * @return the teacher output stream
     */
    public DataOutputStream getTeacherOutput() {
        return teacherOutput;
    }

    /**
     * Returns the teacher's input stream (reads data written by the learner).
     *
     * @return the teacher input stream
     */
    public DataInputStream getTeacherInput() {
        return teacherInput;
    }

    /**
     * Returns the learner's output stream (learner writes here, teacher reads from its input).
     *
     * @return the learner output stream
     */
    public DataOutputStream getLearnerOutput() {
        return learnerOutput;
    }

    /**
     * Returns the learner's input stream (reads data written by the teacher).
     *
     * @return the learner input stream
     */
    public DataInputStream getLearnerInput() {
        return learnerInput;
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        try {
            teacherOutput.close();
            teacherInput.close();
            learnerOutput.close();
            learnerInput.close();

            teacherOutputBuffer.close();
            teacherInputBuffer.close();
            learnerOutputBuffer.close();
            learnerInputBuffer.close();

            server.close();
            teacherSocket.close();
            learnerSocket.close();
        } catch (IOException e) {
            // this is the test code, and we don't want the test to fail because of a close error
            e.printStackTrace();
        }
    }

    /**
     * Do an emergency shutdown of the sockets. Intentionally pulls the rug out from
     * underneath all streams reading/writing the sockets.
     */
    public void disconnect() {
        try {
            server.close();
            teacherSocket.close();
            learnerSocket.close();
        } catch (IOException e) {
            // this is the test code, and we don't want the test to fail because of a close error
            e.printStackTrace();
        }
    }
}
