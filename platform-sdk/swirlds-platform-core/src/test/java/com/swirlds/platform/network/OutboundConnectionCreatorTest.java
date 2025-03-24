// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.network.connection.NotConnectedConnection;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import com.swirlds.platform.network.connectivity.SocketFactory;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OutboundConnectionCreatorTest {
    @Test
    void createConnectionTest() throws IOException {

        final int numNodes = 10;
        final Random r = new Random();
        final Roster roster = RandomRosterBuilder.create(r)
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .build();
        final int thisNodeIndex = r.nextInt(numNodes);
        int otherNodeIndex = r.nextInt(numNodes);
        while (otherNodeIndex == thisNodeIndex) {
            otherNodeIndex = r.nextInt(numNodes);
        }
        final NodeId thisNode =
                NodeId.of(roster.rosterEntries().get(thisNodeIndex).nodeId());
        final NodeId otherNode =
                NodeId.of(roster.rosterEntries().get(otherNodeIndex).nodeId());

        final AtomicBoolean connected = new AtomicBoolean(true);
        final Socket socket = mock(Socket.class);
        doAnswer(i -> connected.get()).when(socket).isConnected();
        doAnswer(i -> connected.get()).when(socket).isBound();
        doAnswer(i -> !connected.get()).when(socket).isClosed();
        doAnswer(i -> {
                    connected.set(false);
                    return null;
                })
                .when(socket)
                .close();

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(byteOut);
        out.writeInt(ByteConstants.COMM_CONNECT);
        out.close();

        final ByteArrayInputStream inputStream = new ByteArrayInputStream(byteOut.toByteArray());
        doAnswer(i -> inputStream).when(socket).getInputStream();
        doAnswer(i -> mock(OutputStream.class)).when(socket).getOutputStream();
        final SocketFactory socketFactory = mock(SocketFactory.class);
        doAnswer(i -> socket).when(socketFactory).createClientSocket(any(), anyInt());

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(getConfig())
                .build();

        final OutboundConnectionCreator occ = new OutboundConnectionCreator(
                platformContext,
                thisNode,
                mock(ConnectionTracker.class),
                socketFactory,
                Utilities.createPeerInfoList(roster, thisNode));

        Connection connection = occ.createConnection(otherNode);
        assertTrue(connection instanceof SocketConnection, "the returned connection should be a socket connection");
        assertEquals(thisNode, connection.getSelfId(), "self ID should match supplied ID");
        assertEquals(otherNode, connection.getOtherId(), "other ID should match supplied ID");
        assertTrue(connection.connected(), "a new connection should be connected");
        connection.disconnect();
        assertFalse(connection.connected(), "should not be connected after calling disconnect()");

        // test exceptions
        Mockito.doThrow(SocketTimeoutException.class).when(socketFactory).createClientSocket(any(), anyInt());
        connection = occ.createConnection(otherNode);
        assertTrue(
                connection instanceof NotConnectedConnection,
                "the returned connection should be a fake not connected connection");

        Mockito.doThrow(SocketException.class).when(socketFactory).createClientSocket(any(), anyInt());
        connection = occ.createConnection(otherNode);
        assertTrue(
                connection instanceof NotConnectedConnection,
                "the returned connection should be a fake not connected connection");

        Mockito.doThrow(IOException.class).when(socketFactory).createClientSocket(any(), anyInt());
        connection = occ.createConnection(otherNode);
        assertTrue(
                connection instanceof NotConnectedConnection,
                "the returned connection should be a fake not connected connection");

        Mockito.doThrow(RuntimeException.class).when(socketFactory).createClientSocket(any(), anyInt());
        connection = occ.createConnection(otherNode);
        assertTrue(
                connection instanceof NotConnectedConnection,
                "the returned connection should be a fake not connected connection");
    }

    @Test
    @DisplayName("Mismatched Version Ignored Test")
    void mismatchedVersionIgnoredTest() throws IOException {

        final int numNodes = 10;
        final Random r = new Random();
        final Roster roster = RandomRosterBuilder.create(r)
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .build();
        final int thisNodeIndex = r.nextInt(numNodes);
        int otherNodeIndex = r.nextInt(numNodes);
        while (otherNodeIndex == thisNodeIndex) {
            otherNodeIndex = r.nextInt(numNodes);
        }
        final NodeId thisNode =
                NodeId.of(roster.rosterEntries().get(thisNodeIndex).nodeId());
        final NodeId otherNode =
                NodeId.of(roster.rosterEntries().get(otherNodeIndex).nodeId());

        final AtomicBoolean connected = new AtomicBoolean(true);
        final Socket socket = mock(Socket.class);
        doAnswer(i -> connected.get()).when(socket).isConnected();
        doAnswer(i -> connected.get()).when(socket).isBound();
        doAnswer(i -> !connected.get()).when(socket).isClosed();
        doAnswer(i -> {
                    connected.set(false);
                    return null;
                })
                .when(socket)
                .close();

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(byteOut);
        out.writeInt(ByteConstants.COMM_CONNECT);
        out.close();

        final ByteArrayInputStream inputStream = new ByteArrayInputStream(byteOut.toByteArray());
        doAnswer(i -> inputStream).when(socket).getInputStream();
        doAnswer(i -> mock(OutputStream.class)).when(socket).getOutputStream();
        final SocketFactory socketFactory = mock(SocketFactory.class);
        doAnswer(i -> socket).when(socketFactory).createClientSocket(any(), anyInt());

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(getConfig())
                .build();

        final OutboundConnectionCreator occ = new OutboundConnectionCreator(
                platformContext,
                thisNode,
                mock(ConnectionTracker.class),
                socketFactory,
                Utilities.createPeerInfoList(roster, thisNode));

        Connection connection = occ.createConnection(otherNode);
        assertTrue(connection instanceof SocketConnection, "the returned connection should be a socket connection");
        assertEquals(thisNode, connection.getSelfId(), "self ID should match supplied ID");
        assertEquals(otherNode, connection.getOtherId(), "other ID should match supplied ID");
        assertTrue(connection.connected(), "a new connection should be connected");
        connection.disconnect();
        assertFalse(connection.connected(), "should not be connected after calling disconnect()");
    }

    @NonNull
    private static Configuration getConfig() {
        return new TestConfigBuilder()
                .withValue(SocketConfig_.BUFFER_SIZE, "100")
                .withValue(SocketConfig_.GZIP_COMPRESSION, false)
                .getOrCreateConfig();
    }
}
