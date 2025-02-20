// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Utility class for generating paired streams for synchronization tests.
 */
public class PairedStreams implements AutoCloseable {

    protected BufferedOutputStream teacherOutputBuffer;
    protected MerkleDataOutputStream teacherOutput;

    protected BufferedInputStream teacherInputBuffer;
    protected MerkleDataInputStream teacherInput;

    protected BufferedOutputStream learnerOutputBuffer;
    protected MerkleDataOutputStream learnerOutput;
    protected BufferedInputStream learnerInputBuffer;
    protected MerkleDataInputStream learnerInput;

    protected Socket teacherSocket;
    protected Socket learnerSocket;
    protected ServerSocket server;

    public PairedStreams() throws IOException {

        server = new ServerSocket(0);
        teacherSocket = new Socket("127.0.0.1", server.getLocalPort());
        learnerSocket = server.accept();

        teacherOutputBuffer = new BufferedOutputStream(teacherSocket.getOutputStream());
        teacherOutput = new MerkleDataOutputStream(teacherOutputBuffer);

        teacherInputBuffer = new BufferedInputStream(teacherSocket.getInputStream());
        teacherInput = new MerkleDataInputStream(teacherInputBuffer);

        learnerOutputBuffer = new BufferedOutputStream(learnerSocket.getOutputStream());
        learnerOutput = new MerkleDataOutputStream(learnerOutputBuffer);

        learnerInputBuffer = new BufferedInputStream(learnerSocket.getInputStream());
        learnerInput = new MerkleDataInputStream(learnerInputBuffer);
    }

    public MerkleDataOutputStream getTeacherOutput() {
        return teacherOutput;
    }

    public MerkleDataInputStream getTeacherInput() {
        return teacherInput;
    }

    public MerkleDataOutputStream getLearnerOutput() {
        return learnerOutput;
    }

    public MerkleDataInputStream getLearnerInput() {
        return learnerInput;
    }

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
