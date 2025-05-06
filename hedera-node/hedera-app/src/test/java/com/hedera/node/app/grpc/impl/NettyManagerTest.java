// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.grpc.impl.netty.NettyGrpcServerManager;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.VersionedConfigImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.hiero.base.constructable.ConstructableRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class NettyManagerTest extends GrpcTestBase {
    private static final ScheduledExecutorService METRIC_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private NettyGrpcServerManager createServerManager(@NonNull final TestSource testConfig) {
        final var config = createConfig(testConfig);
        return new NettyGrpcServerManager(
                () -> new VersionedConfigImpl(config, 1),
                new ServicesRegistryImpl(ConstructableRegistry.getInstance(), config),
                (req, res) -> {},
                (req, res) -> {},
                (req, res) -> {},
                metrics);
    }

    /**
     * If the port number is set to 0, then we allow the computer to choose an "ephemeral" port for us automatically.
     * We won't know ahead of time what the port number is.
     */
    @Test
    @DisplayName("Ephemeral ports are supported")
    @Timeout(value = 15)
    void ephemeralPorts() {
        // Given a server with 0 as the port number for both port and tls port
        final var subject = createServerManager(new TestSource().withPort(0).withTlsPort(0));

        try {
            // When we start the server
            subject.start();

            // Then we find that the server has started
            assertThat(subject.isRunning()).isTrue();
            // And that the port numbers are no longer 0
            assertThat(subject.port()).isNotZero();
            assertThat(subject.tlsPort()).isNotZero();
            // And that the server is listening on the ports
            // FUTURE: I'm only testing the plain port and not the tls port because these tests do not yet support tls
            // properly. But when they do, we should check the tls port too.
            assertThat(isListening(subject.port())).isTrue();
        } finally {
            subject.stop();
        }
    }

    /**
     * Verifies that when actual port numbers are given, they are used. This test is inherently a little unstable,
     * because nothing can be listening on the port that we select. We will attempt to make it more stable by
     * selecting ports at random, and checking whether they are in use before proceeding.
     */
    @Test
    @DisplayName("Non-ephemeral ports are supported")
    @Timeout(value = 15)
    void nonEphemeralPorts() {
        // Given a server configured with actual port numbers
        final var subject = createServerManager(new TestSource().withFreePort().withFreeTlsPort());

        try {
            // When we start the server
            subject.start();

            // Then we find that the server has started
            assertThat(subject.isRunning()).isTrue();
            // And that it is listening on the ports
            // FUTURE: I'm only testing the plain port and not the tls port because these tests do not yet support tls
            // properly. But when they do, we should check the tls port too.
            assertThat(isListening(subject.port())).isTrue();
        } finally {
            subject.stop();
        }
    }

    @Test
    @DisplayName("Starting a server twice throws")
    @Timeout(value = 15)
    void startingTwice() {
        // Given a server with a configuration that will start
        final var subject = createServerManager(new TestSource());

        // When we start the server, we find that it starts. And when we start it again, it throws.
        try {
            subject.start();
            assertThat(subject.port()).isNotZero();
            assertThat(subject.isRunning()).isTrue();
            assertThat(isListening(subject.port())).isTrue();
            assertThatThrownBy(subject::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Server already started");
        } finally {
            subject.stop();
        }
    }

    @Test
    @DisplayName("Stopping a server")
    @Timeout(value = 15)
    void stoppingAStartedServer() {
        // Given a server with a configuration that will start
        final var subject = createServerManager(new TestSource());

        // When we start the server, we find that it starts, and when we stop it, we find that it stops again.
        try {
            subject.start();
            assertThat(subject.port()).isNotZero();
            assertThat(subject.isRunning()).isTrue();
            assertThat(isListening(subject.port())).isTrue();
        } finally {
            subject.stop();
        }

        assertThat(subject.isRunning()).isFalse();
        assertThat(subject.port()).isEqualTo(-1);
        assertThat(subject.tlsPort()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Stopping a server is idempotent")
    @Timeout(value = 15)
    void stoppingIsIdempotent() {
        // Given a server with a configuration that will start
        final var subject = createServerManager(new TestSource());

        // When we start the server, it starts.
        try {
            subject.start();
            assertThat(subject.port()).isNotZero();
        } finally {
            subject.stop();
        }

        // And if we stop it multiple times, this is OK
        assertThat(subject.isRunning()).isFalse();
        subject.stop();
        assertThat(subject.isRunning()).isFalse();
        assertThat(subject.port()).isEqualTo(-1);
        assertThat(subject.tlsPort()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Restarting a server")
    @Timeout(value = 30)
    void restart() {
        // Given a server with a configuration that will start
        final var subject = createServerManager(new TestSource());

        // We can cycle over start / stop / start / stop cycles, and it is all good
        for (int i = 0; i < 2; i++) {
            try {
                subject.start();
                assertThat(subject.port()).isNotZero();
                assertThat(subject.isRunning()).isTrue();
                assertThat(isListening(subject.port())).isTrue();
            } finally {
                subject.stop();
                assertThat(subject.isRunning()).isFalse();
                assertThat(subject.port()).isEqualTo(-1);
                assertThat(subject.tlsPort()).isEqualTo(-1);
            }
        }
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("Starting a server with a port already in use but is then released")
    @SuppressWarnings("java:S2925") // suppressing the warning about TimeUnit.MILLISECONDS.sleep usage in tests
    void portBecomesFreeEventually() throws Exception {
        // Given a server with a configuration that will start
        final var testConfig = new TestSource()
                .withFreePort()
                .withFreeTlsPort()
                .withStartRetries(10)
                .withStartRetryIntervalMs(10);
        final var subject = createServerManager(testConfig);
        // And a server socket listening on the port that the server intends to use
        try (final var serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(testConfig.port()));
            assertThat(serverSocket.isBound()).isTrue();

            // When we start the gRPC server on that same port
            final LogCaptor logCaptor = new LogCaptor(LogManager.getLogger(NettyGrpcServerManager.class));
            final var th = new Thread(subject::start);
            th.start();

            // Wait for the server to try again to startup. We wait until we've seen that the server actually tried
            // to start, and then we will proceed with the rest of the test (we want to make sure the port was occupied
            // when the server tried to start).
            while (true) {
                assertThat(subject.isRunning()).isFalse();
                final var logs = String.join("\n", logCaptor.infoLogs());
                System.out.println(logs);
                if (logs.contains("Still trying to start server... 9 tries remaining")) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(10);
            }

            // And when we stop the socket that was using the port
            serverSocket.close();

            // Wait for the server to start
            while (!subject.isRunning()) {
                TimeUnit.MILLISECONDS.sleep(10);
            }

            // Then we find that the server finally started up!
            assertThat(subject.isRunning()).isTrue();
            // FUTURE: I'm only testing the plain port and not the tls port because these tests do not yet support tls
            // properly. But when they do, we should check the tls port too.
            assertThat(isListening(subject.port())).isTrue();
        } finally {
            subject.stop();
        }
    }

    /**
     * Start with a port that is already in use, and observe that after N retries and X millis per retry, the server
     * ultimately fails to start.
     *
     * <p>NOTE: The Helidon server appears to have its own 5-second timeout while starting. As such, I have to have a
     * longer timeout value here so the timeout will not expire prematurely.
     */
    @Test
    @Timeout(value = 50)
    @DisplayName("Starting a server with a port already in use")
    void portInUse() throws Exception {
        // Given a server with a configuration that will start
        final var testConfig = new TestSource()
                .withFreePort()
                .withFreeTlsPort()
                .withStartRetries(1)
                .withStartRetryIntervalMs(10);
        final var subject = createServerManager(testConfig);
        // And a server socket listening on the port that the server intends to use
        try (final var serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(testConfig.port()));
            assertThat(serverSocket.isBound()).isTrue();

            // Start the gRPC server, trying to use the same port, which will eventually give up and throw
            assertThatThrownBy(subject::start)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to start gRPC server");
        } finally {
            subject.stop();
        }
    }
}
