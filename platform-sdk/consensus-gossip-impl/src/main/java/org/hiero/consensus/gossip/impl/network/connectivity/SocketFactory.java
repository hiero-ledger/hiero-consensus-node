// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.connectivity;

import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.NetworkEndpoint;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.model.node.NodeId;

/**
 * Creates, binds and connects server and client sockets
 */
public interface SocketFactory {

    static final Logger logger = LogManager.getLogger(SocketFactory.class);

    /** The IPv4 address to listen all interface: [0.0.0.0]. */
    byte[] ALL_INTERFACES = new byte[] {0, 0, 0, 0};

    int IP_TOP_MIN = 0;
    int IP_TOP_MAX = 255;

    static boolean isIpTopInRange(final int ipTos) {
        return IP_TOP_MIN <= ipTos && ipTos <= IP_TOP_MAX;
    }

    /**
     * Configures and binds the provided ServerSocket
     *
     * @param serverSocket
     * 		the socket to configure and bind
     * @param socketConfig
     * 		the configuration for the socket
     * @param gossipConfig
     *    the gossip configuration
     * @param port
     * 		the TCP port to bind
     * @throws IOException
     * 		if the bind is unsuccessful
     */
    static void configureAndBind(
            @NonNull final NodeId selfId,
            @NonNull final ServerSocket serverSocket,
            @NonNull final SocketConfig socketConfig,
            @NonNull final GossipConfig gossipConfig,
            final int port)
            throws IOException {
        Objects.requireNonNull(selfId);
        Objects.requireNonNull(serverSocket);
        Objects.requireNonNull(socketConfig);
        Objects.requireNonNull(gossipConfig);

        final NetworkEndpoint networkEndpoint = gossipConfig
                .getInterfaceBindings(selfId.id())
                .orElseGet(() -> {
                    try {
                        return new NetworkEndpoint(selfId.id(), InetAddress.getByAddress(ALL_INTERFACES), port);
                    } catch (UnknownHostException e) {
                        throw new RuntimeException("Host 'ALL_INTERFACES' not found", e);
                    }
                });

        if (isIpTopInRange(socketConfig.ipTos())) {
            // set the IP_TOS option
            serverSocket.setOption(java.net.StandardSocketOptions.IP_TOS, socketConfig.ipTos());
        }
        final InetSocketAddress endpoint = new InetSocketAddress(networkEndpoint.hostname(), networkEndpoint.port());
        serverSocket.setReuseAddress(true);

        // FIXME: it's an experiment, remove it
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
//        serverSocket.setReceiveBufferSize(reconnectBufferBytes);

        logger.warn(SOCKET_EXCEPTIONS.getMarker(),
                "[PRE BIND] Server socket receive buffer size: {}",
                serverSocket.getReceiveBufferSize());

        serverSocket.bind(endpoint); // try to grab a port on this computer
        // do NOT do clientSocket.setSendBufferSize or clientSocket.setReceiveBufferSize
        // because it causes a major bug in certain situations

        serverSocket.setSoTimeout(socketConfig.timeoutServerAcceptConnect());

        logger.warn(SOCKET_EXCEPTIONS.getMarker(),
                "[POST BIND] Server socket receive buffer size: {}",
                serverSocket.getReceiveBufferSize());
    }

    /**
     * Configures and connects the provided client Socket
     *
     * @param clientSocket
     * 		the socket to configure and connect
     * @param socketConfig
     * 		the configuration for the socket
     * @param hostname
     * 		the address to connect to
     * @param port
     * 		the TCP port to connect to
     * @throws IOException
     * 		if the connections fails
     */
    static void configureAndConnect(
            @NonNull final Socket clientSocket,
            @NonNull final SocketConfig socketConfig,
            @NonNull final String hostname,
            final int port)
            throws IOException {
        if (isIpTopInRange(socketConfig.ipTos())) {
            // set the IP_TOS option
            clientSocket.setOption(java.net.StandardSocketOptions.IP_TOS, socketConfig.ipTos());
        }

//        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
//        clientSocket.setReceiveBufferSize(reconnectBufferBytes);
//        clientSocket.setSendBufferSize(reconnectBufferBytes);

        logger.warn(SOCKET_EXCEPTIONS.getMarker(),
                "[PRE CONNECT] Client socket send buffer size: {}",
                clientSocket.getSendBufferSize());
        logger.warn(SOCKET_EXCEPTIONS.getMarker(),
                "[PRE CONNECT] Client socket receive buffer size: {}",
                clientSocket.getReceiveBufferSize());

        clientSocket.setSoTimeout(socketConfig.timeoutSyncClientSocket());
        clientSocket.setTcpNoDelay(socketConfig.tcpNoDelay());
        // do NOT do clientSocket.setSendBufferSize or clientSocket.setReceiveBufferSize
        // because it causes a major bug in certain situations
        clientSocket.connect(new InetSocketAddress(hostname, port), socketConfig.timeoutSyncClientConnect());

        logger.warn(SOCKET_EXCEPTIONS.getMarker(),
                "[POST CONNECT] Client socket send buffer size: {}",
                clientSocket.getSendBufferSize());
        logger.warn(SOCKET_EXCEPTIONS.getMarker(),
                "[POST CONNECT] Client socket receive buffer size: {}",
                clientSocket.getReceiveBufferSize());
    }

    /**
     * Create a new ServerSocket, then binds it to the given port on all interfaces
     *
     * @param port
     * 		the port to bind to
     * @return a new server socket
     * @throws IOException
     * 		if the socket cannot be created
     */
    @NonNull
    ServerSocket createServerSocket(final int port) throws IOException;

    /**
     * Create a new Socket, then connect to the given ip and port.
     *
     * @param hostname
     * 		the address to connect to
     * @param port
     * 		the port to connect to
     * @return the new socket
     * @throws IOException
     * 		if the connection cannot be made
     */
    @NonNull
    Socket createClientSocket(@NonNull final String hostname, final int port) throws IOException;

    /**
     * Reloads the trust store with peer certificates
     *
     * @param peers the updated list of peers
     */
    void reload(@NonNull final Collection<PeerInfo> peers);
}
