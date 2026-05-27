// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures.sync;

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

    /**
     * {@inheritDoc}
     *
     * <p>Closes all streams and sockets. Closing the outer {@link DataOutputStream}/
     * {@link DataInputStream} wrappers also closes their underlying buffered streams, so the
     * buffered layers are not closed redundantly. Any {@link IOException}s encountered are
     * accumulated; the first is thrown with the rest attached as suppressed exceptions.
     *
     * @throws IOException if one or more resources fail to close
     */
    @Override
    public void close() throws IOException {
        IOException firstException = null;
        for (final AutoCloseable resource : new AutoCloseable[] {
            teacherOutput, teacherInput, learnerOutput, learnerInput, server, teacherSocket, learnerSocket
        }) {
            try {
                resource.close();
            } catch (IOException e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
            } catch (Exception e) {
                // AutoCloseable.close() declares Exception; wrap any non-IO surprise
                if (firstException == null) {
                    firstException = new IOException("Unexpected error during close", e);
                } else {
                    firstException.addSuppressed(e);
                }
            }
        }
        if (firstException != null) {
            throw firstException;
        }
    }

    /**
     * Sets the read timeout on the teacher's socket. A blocked read that does not receive data
     * within {@code timeoutMs} milliseconds will throw a {@link java.net.SocketTimeoutException}.
     * May be called at any point after construction; takes effect on the next blocking read.
     *
     * @param timeoutMs timeout in milliseconds; 0 means wait indefinitely (the default)
     * @throws java.net.SocketException if the socket option cannot be set
     */
    public void setTeacherTimeout(final int timeoutMs) throws java.net.SocketException {
        teacherSocket.setSoTimeout(timeoutMs);
    }

    /**
     * Sets the read timeout on the learner's socket. A blocked read that does not receive data
     * within {@code timeoutMs} milliseconds will throw a {@link java.net.SocketTimeoutException}.
     * May be called at any point after construction; takes effect on the next blocking read.
     *
     * @param timeoutMs timeout in milliseconds; 0 means wait indefinitely (the default)
     * @throws java.net.SocketException if the socket option cannot be set
     */
    public void setLearnerTimeout(final int timeoutMs) throws java.net.SocketException {
        learnerSocket.setSoTimeout(timeoutMs);
    }

    /**
     * Closes only the teacher's socket, leaving the learner's socket open.
     * The learner's next read will throw an {@link java.io.EOFException} or
     * {@link java.net.SocketException} ("Connection reset"), simulating an
     * asymmetric teardown where the teacher side drops the connection.
     */
    public void disconnectTeacher() {
        try {
            teacherSocket.close();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    /**
     * Closes only the learner's socket, leaving the teacher's socket open.
     * The teacher's next read will throw an {@link java.io.EOFException} or
     * {@link java.net.SocketException} ("Connection reset"), simulating an
     * asymmetric teardown where the learner side drops the connection.
     */
    public void disconnectLearner() {
        try {
            learnerSocket.close();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    /**
     * Do an emergency shutdown of the sockets. Intentionally pulls the rug out from
     * underneath all streams reading/writing the sockets.
     */
    public void disconnect() {
        disconnectTeacher();
        disconnectLearner();
        try {
            server.close();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }
}
