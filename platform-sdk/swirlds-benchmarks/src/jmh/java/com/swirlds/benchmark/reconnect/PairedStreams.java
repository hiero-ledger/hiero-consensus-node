// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility class for generating paired streams for synchronization tests.
 */
public class PairedStreams implements AutoCloseable {

    protected BufferedOutputStream teacherOutputBuffer;
    protected SerializableDataOutputStream teacherOutput;

    protected BufferedInputStream teacherInputBuffer;
    protected SerializableDataInputStream teacherInput;

    protected BufferedOutputStream learnerOutputBuffer;
    protected SerializableDataOutputStream learnerOutput;
    protected BufferedInputStream learnerInputBuffer;
    protected SerializableDataInputStream learnerInput;

    protected Socket teacherSocket;
    protected Socket learnerSocket;
    protected ServerSocket server;

    public PairedStreams(
            @NonNull final NodeId nodeId,
            @NonNull final SocketConfig socketConfig,
            @NonNull final GossipConfig gossipConfig)
            throws IOException {

        // open server socket
        server = new ServerSocket();
        SocketFactory.configureAndBind(nodeId, server, socketConfig, gossipConfig, 0);

        teacherSocket = new Socket("127.0.0.1", server.getLocalPort());
        learnerSocket = server.accept();

        teacherOutputBuffer = new BufferedOutputStream(teacherSocket.getOutputStream());
        teacherOutput = new SerializableDataOutputStream(teacherOutputBuffer);

        teacherInputBuffer = new BufferedInputStream(teacherSocket.getInputStream());
        teacherInput = new SerializableDataInputStream(teacherInputBuffer);

        learnerOutputBuffer = new BufferedOutputStream(learnerSocket.getOutputStream());
        learnerOutput = new SerializableDataOutputStream(learnerOutputBuffer);

        learnerInputBuffer = new BufferedInputStream(learnerSocket.getInputStream());
        learnerInput = new SerializableDataInputStream(learnerInputBuffer);
    }

    public SerializableDataOutputStream getTeacherOutput() {
        return teacherOutput;
    }

    public SerializableDataInputStream getTeacherInput() {
        return teacherInput;
    }

    public SerializableDataOutputStream getLearnerOutput() {
        return learnerOutput;
    }

    public SerializableDataInputStream getLearnerInput() {
        return learnerInput;
    }

    @Override
    public void close() throws IOException {
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
    }

    /**
     * Do an emergency shutdown of the sockets. Intentionally pulls the rug out from
     * underneath all streams reading/writing the sockets.
     */
    public void disconnect() throws IOException {
        server.close();
        teacherSocket.close();
        learnerSocket.close();
    }
}
