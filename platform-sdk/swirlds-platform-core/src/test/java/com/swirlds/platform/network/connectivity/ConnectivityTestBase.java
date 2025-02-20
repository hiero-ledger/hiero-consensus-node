// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connectivity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.network.SocketConfig;
import com.swirlds.platform.network.SocketConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class ConnectivityTestBase {

    protected static final String STRING_IP = "127.0.0.1";
    protected static final SocketConfig NO_IP_TOS;
    protected static final SocketConfig IP_TOS;
    protected static final Configuration TLS_NO_IP_TOS_CONFIG;
    protected static final Configuration TLS_IP_TOS_CONFIG;
    protected static final byte[] TEST_DATA = new byte[] {1, 2, 3};

    static {
        TLS_NO_IP_TOS_CONFIG =
                new TestConfigBuilder().withValue(SocketConfig_.IP_TOS, "-1").getOrCreateConfig();
        TLS_IP_TOS_CONFIG =
                new TestConfigBuilder().withValue(SocketConfig_.IP_TOS, "100").getOrCreateConfig();

        final Configuration configurationNoIpTos =
                new TestConfigBuilder().withValue(SocketConfig_.IP_TOS, "-1").getOrCreateConfig();
        NO_IP_TOS = configurationNoIpTos.getConfigData(SocketConfig.class);

        final Configuration configurationIpTos =
                new TestConfigBuilder().withValue(SocketConfig_.IP_TOS, "100").getOrCreateConfig();
        IP_TOS = configurationIpTos.getConfigData(SocketConfig.class);
    }

    /**
     * creates a server socket thread for testing purposes
     *
     * @param serverSocket the server socket to listen on
     */
    @NonNull
    static Thread createSocketThread(@NonNull final ServerSocket serverSocket) {
        return new Thread(() -> {
            try {
                final Socket s = acceptAndVerify(serverSocket);
                s.close();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            } finally {
                // for some reason, AutoClosable does not seem to close in time, and subsequent tests fail if used
                try {
                    serverSocket.close();
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * Creates a socket thread which runs continuously until asked to close
     * - verifies the transferred data is correct
     * - leaves the server socket open until told to close
     *
     * @param serverSocket the server socket
     * @param stopFlag     flag for stopping the thread
     */
    @NonNull
    static Thread createSocketThread(@NonNull final ServerSocket serverSocket, @NonNull final AtomicBoolean stopFlag) {
        Objects.requireNonNull(serverSocket);
        Objects.requireNonNull(stopFlag);
        final Thread thread = new Thread(() -> {
            Socket s = null;
            try {
                while (!stopFlag.get()) {
                    try {
                        s = acceptAndVerify(serverSocket);
                    } catch (final IOException e) {
                        // ignore the exception, go reopen the socket
                    }
                }
            } finally {
                try {
                    if (s != null) {
                        s.close();
                    }
                    serverSocket.close();
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        thread.start();
        return thread;
    }

    /**
     * Sends a message to a listening server socket to be read and verified by a listening server
     *
     * @param serverThread the thread the server socket is listening on
     * @param clientSocket the client socket
     */
    static void testSocket(@NonNull final Thread serverThread, @NonNull final Socket clientSocket) throws Throwable {
        final AtomicReference<Throwable> threadException = new AtomicReference<>();
        serverThread.setUncaughtExceptionHandler((t, e) -> threadException.set(e));
        clientSocket.getOutputStream().write(TEST_DATA);

        if (threadException.get() != null) {
            throw threadException.get();
        }
    }

    @NonNull
    private static Socket acceptAndVerify(@NonNull final ServerSocket serverSocket) throws IOException {
        final Socket s = serverSocket.accept();
        final byte[] bytes = s.getInputStream().readNBytes(TEST_DATA.length);
        assertArrayEquals(TEST_DATA, bytes, "Data read from socket must be the same as the data written");
        return s;
    }
}
