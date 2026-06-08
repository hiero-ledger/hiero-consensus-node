// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility class for generating paired streams for synchronization tests.
 */
public class PairedStreams implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(PairedStreams.class);

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
        teacherOutput = new DataOutputStream(teacherOutputBuffer);

        teacherInputBuffer = new BufferedInputStream(teacherSocket.getInputStream());
        teacherInput = new DataInputStream(teacherInputBuffer);

        learnerOutputBuffer = new BufferedOutputStream(learnerSocket.getOutputStream());
        learnerOutput = new DataOutputStream(learnerOutputBuffer);

        learnerInputBuffer = new BufferedInputStream(learnerSocket.getInputStream());
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
                learnerInputBuffer,
                server,
                teacherSocket,
                learnerSocket);
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
     * Do an emergency shutdown of the sockets. Intentionally pulls the rug out from
     * underneath all streams reading/writing the sockets.
     */
    public void disconnect() throws IOException {
        server.close();
        teacherSocket.close();
        learnerSocket.close();
    }
}
