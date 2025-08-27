// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.swirlds.platform.network.connectivity.ConnectionServer;
import com.swirlds.platform.network.connectivity.InboundConnectionHandler;
import com.swirlds.platform.network.connectivity.SocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConnectionServerTest {

    @Test
    void createConnectionTest() throws IOException, InterruptedException {
        final Socket socket = mock(Socket.class);
        final ServerSocket serverSocket = mock(ServerSocket.class);
        final AtomicBoolean serverSocketClosed = new AtomicBoolean(false);
        doAnswer(i -> serverSocketClosed.get()).when(serverSocket).isClosed();
        doAnswer(i -> {
                    // unbind the socket after calling accept
                    serverSocketClosed.set(true);
                    return socket;
                })
                .when(serverSocket)
                .accept();
        final SocketFactory socketFactory = mock(SocketFactory.class);
        doAnswer(i -> serverSocket).when(socketFactory).createServerSocket(anyInt());
        final AtomicReference<Socket> connectionHandler = new AtomicReference<>(null);

        final ConnectionServer server =
                new ConnectionServer(getStaticThreadManager(), 0, socketFactory, connectionHandler::set);

        server.run();
        Assertions.assertSame(
                socket,
                connectionHandler.get(),
                "the socket provided by accept() should have been passed to the connection handler");

        // test interrupt
        serverSocketClosed.set(false);
        doAnswer(i -> {
                    throw new SocketTimeoutException();
                })
                .when(serverSocket)
                .accept();
        Thread.currentThread().interrupt();
        Assertions.assertThrows(InterruptedException.class, server::run);
    }

    @Test
    void createPeerConnectionTest() throws IOException, InterruptedException {
        final Socket socket = mock(Socket.class);
        final ServerSocket serverSocket = mock(ServerSocket.class);
        final AtomicBoolean serverSocketClosed = new AtomicBoolean(false);
        doAnswer(i -> serverSocketClosed.get()).when(serverSocket).isClosed();
        doAnswer(i -> {
                    // unbind the socket after calling accept
                    serverSocketClosed.set(true);
                    return socket;
                })
                .when(serverSocket)
                .accept();
        final SocketFactory socketFactory = mock(SocketFactory.class);
        doAnswer(i -> serverSocket).when(socketFactory).createServerSocket(anyInt());

        final InboundConnectionHandler handler = mock(InboundConnectionHandler.class);

        final PeerConnectionServer server =
                new PeerConnectionServer(getStaticThreadManager(), 0, handler, socketFactory, 1);

        server.run();

        Mockito.verify(handler, Mockito.times(1)).handle(socket);

        // test interrupt
        serverSocketClosed.set(false);
        doAnswer(i -> {
                    throw new SocketTimeoutException();
                })
                .when(serverSocket)
                .accept();
        Thread.currentThread().interrupt();
        Assertions.assertThrows(InterruptedException.class, server::run);
    }
}
